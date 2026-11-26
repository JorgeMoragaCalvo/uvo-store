package org.uvo.uvostore.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.uvo.uvostore.entity.pos.PosWebhookReceipt;

import java.time.Instant;

public interface PosWebhookReceiptRepository extends JpaRepository<PosWebhookReceipt, Long> {

    /**
     * POS-3. Registra el webhook y dice si es la primera vez: 1 si lo es, 0 si ya estaba.
     *
     * <p>Una sola sentencia, y no "buscar y después insertar": con dos repeticiones simultáneas, la segunda
     * espera en el índice único a que la primera confirme y obtiene 0. Comprobar antes dejaría pasar a las
     * dos.
     *
     * <p>Lo llama un filtro, fuera de cualquier transacción, así que confirma por su cuenta en el acto: el
     * guarda tiene que valer para la petición siguiente aunque esta falle después.
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO pos_webhook_receipts (key_id, nonce, received_at)
            VALUES (:keyId, :nonce, now())
            ON CONFLICT (key_id, nonce) DO NOTHING
            """, nativeQuery = true)
    int recordIfNew(@Param("keyId") String keyId, @Param("nonce") String nonce);

    @Transactional
    @Modifying
    @Query("delete from PosWebhookReceipt r where r.receivedAt < :cutoff")
    int deleteReceivedBefore(@Param("cutoff") Instant cutoff);
}
