package org.uvo.uvostore.service.pos;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.pos.PosConnection;
import org.uvo.uvostore.entity.pos.PosConnectionKey;
import org.uvo.uvostore.entity.pos.PosConnectionKey.Direction;
import org.uvo.uvostore.repository.PosConnectionKeyRepository;
import org.uvo.uvostore.security.UvoHmac;
import org.uvo.uvostore.service.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * IMP-08. Las llaves de firma de las conexiones con el POS (contrato §2): alta, rotación y revocación.
 *
 * <p>Una rotación no corta nada: la llave anterior de esa dirección sigue sirviendo {@link #ROTATION_GRACE} más, para
 * que el POS y la tienda cambien cada uno a su ritmo. Para salir se firma siempre con la más nueva. Una revocación
 * es inmediata: desde ese momento ese {@code key_id} da 401.
 *
 * <p>Ningún mensaje de error repite un secreto.
 */
@Service
public class PosConnectionKeyService implements PosSigningKeys {

    /** Cuánto sigue viva la llave anterior después de una rotación (§2). */
    public static final Duration ROTATION_GRACE = Duration.ofDays(7);

    private final PosConnectionKeyRepository repository;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public PosConnectionKeyService(PosConnectionKeyRepository repository) {
        this(repository, Clock.systemUTC());
    }

    PosConnectionKeyService(PosConnectionKeyRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** La llave con la que la tienda firma lo que manda a esta conexión: la vigente más nueva. */
    @Transactional(readOnly = true)
    public Optional<PosConnectionKey> currentOutbound(PosConnection connection) {
        Instant now = clock.instant();
        return repository.findByConnection(connection.getId(), Direction.OUTBOUND).stream()
                .filter(key -> key.isUsableAt(now))
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SigningKey> outboundFor(PosConnection connection) {
        return currentOutbound(connection).map(key -> new SigningKey(key.getKeyId(), key.getSecret()));
    }

    /** Una llave de entrada que sirve ahora (no revocada ni retirada), con su conexión y su tienda. */
    @Transactional(readOnly = true)
    public Optional<PosConnectionKey> findUsableInbound(String keyId) {
        if (!UvoHmac.isValidKeyId(keyId)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return repository.findWithConnection(Direction.INBOUND, keyId).filter(key -> key.isUsableAt(now));
    }

    /** Las llaves de una conexión, sin secretos, para el panel. */
    @Transactional(readOnly = true)
    public List<KeyView> describe(PosConnection connection) {
        Instant now = clock.instant();
        return java.util.stream.Stream.of(Direction.OUTBOUND, Direction.INBOUND)
                .flatMap(direction -> repository.findByConnection(connection.getId(), direction).stream())
                .map(key -> new KeyView(key.getDirection(), key.getKeyId(), key.getCreatedAt(), key.getRetiresAt(),
                        key.getRevokedAt(), key.isUsableAt(now)))
                .toList();
    }

    /** Si la conexión tiene una llave vigente en cada dirección: sin eso no puede activarse. */
    @Transactional(readOnly = true)
    public boolean hasUsableKeys(PosConnection connection) {
        Instant now = clock.instant();
        return connection.getId() != null
                && repository.findByConnection(connection.getId(), Direction.OUTBOUND).stream().anyMatch(k -> k.isUsableAt(now))
                && repository.findByConnection(connection.getId(), Direction.INBOUND).stream().anyMatch(k -> k.isUsableAt(now));
    }

    /**
     * Instala una llave. Si es la que ya está vigente (mismo {@code key_id} y mismo secreto), no hace nada y devuelve
     * false: así un reintento del alta no rota nada. Si es nueva, las vigentes de esa dirección pasan a retirarse en
     * {@link #ROTATION_GRACE} y devuelve true.
     *
     * @throws BusinessException si el {@code key_id} o el secreto no sirven, o si el {@code key_id} ya existe con otro
     *                           secreto o en otra conexión
     */
    @Transactional
    public boolean install(PosConnection connection, Direction direction, String keyId, String secretBase64) {
        String label = direction == Direction.OUTBOUND ? "tienda→POS" : "POS→tienda";
        if (!UvoHmac.isValidKeyId(keyId)) {
            throw new BusinessException("El key_id de la llave " + label
                    + " solo puede tener letras, números, punto, guion y guion bajo (hasta 100).");
        }
        if (!UvoHmac.isUsableSecret(secretBase64)) {
            throw new BusinessException("El secreto de la llave " + label + " debe ser base64 de al menos "
                    + UvoHmac.MIN_SECRET_BYTES + " bytes.");
        }
        String secret = secretBase64.trim();
        Optional<PosConnectionKey> existing = repository.findWithConnection(direction, keyId);
        if (existing.isPresent()) {
            PosConnectionKey key = existing.get();
            boolean same = key.getConnection().getId().equals(connection.getId())
                    && key.getRevokedAt() == null
                    && MessageDigest.isEqual(key.getSecret().getBytes(StandardCharsets.UTF_8),
                    secret.getBytes(StandardCharsets.UTF_8));
            if (same) {
                return false;
            }
            throw new BusinessException("El key_id " + keyId + " de la llave " + label
                    + " ya se usó. Una rotación necesita un key_id nuevo.");
        }

        Instant now = clock.instant();
        Instant retirement = now.plus(ROTATION_GRACE);
        if (connection.getId() != null) {
            for (PosConnectionKey previous : repository.findByConnection(connection.getId(), direction)) {
                if (previous.isUsableAt(now) && (previous.getRetiresAt() == null || previous.getRetiresAt().isAfter(retirement))) {
                    previous.setRetiresAt(retirement);
                }
            }
        }
        PosConnectionKey key = new PosConnectionKey();
        key.setConnection(connection);
        key.setDirection(direction);
        key.setKeyId(keyId);
        key.setSecret(secret);
        key.setCreatedAt(now);
        repository.save(key);
        return true;
    }

    /**
     * Si las dos llaves son exactamente las vigentes de la conexión. El alta repetida del POS lo usa para saber si es
     * el mismo alta.
     */
    @Transactional(readOnly = true)
    public boolean matchesCurrent(PosConnection connection, String outboundKeyId, String outboundSecret,
                                  String inboundKeyId, String inboundSecret) {
        return matches(connection, Direction.OUTBOUND, outboundKeyId, outboundSecret)
                && matches(connection, Direction.INBOUND, inboundKeyId, inboundSecret);
    }

    private boolean matches(PosConnection connection, Direction direction, String keyId, String secret) {
        Instant now = clock.instant();
        return secret != null && repository.findByConnection(connection.getId(), direction).stream()
                .filter(key -> key.isUsableAt(now))
                .findFirst()
                .filter(key -> key.getKeyId().equals(keyId))
                .filter(key -> MessageDigest.isEqual(key.getSecret().getBytes(StandardCharsets.UTF_8),
                        secret.trim().getBytes(StandardCharsets.UTF_8)))
                .isPresent();
    }

    /**
     * Revoca en el acto una llave de la conexión. Devuelve false si ya estaba revocada.
     *
     * @throws BusinessException si la conexión no tiene esa llave
     */
    @Transactional
    public boolean revoke(PosConnection connection, Direction direction, String keyId) {
        PosConnectionKey key = repository.findWithConnection(direction, keyId)
                .filter(found -> found.getConnection().getId().equals(connection.getId()))
                .orElseThrow(() -> new BusinessException("La conexión no tiene la llave " + keyId + "."));
        if (key.getRevokedAt() != null) {
            return false;
        }
        key.setRevokedAt(clock.instant());
        return true;
    }

    /** Una llave sin su secreto. */
    public record KeyView(Direction direction, String keyId, Instant createdAt, Instant retiresAt, Instant revokedAt,
                          boolean usable) {
    }
}
