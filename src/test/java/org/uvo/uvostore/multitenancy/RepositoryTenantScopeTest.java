package org.uvo.uvostore.multitenancy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.ResolvableType;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.Repository;
import org.uvo.uvostore.entity.customer.Customer;
import org.uvo.uvostore.entity.tenant.Store;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PROD-17b. Ninguna consulta sobre una entidad de tienda puede leer o contar a ciegas entre tiendas.
 *
 * <p>Es lo que el compilador no puede garantizar. Borrar las consultas sin {@code storeId} que nadie
 * llamaba cierra el hueco de hoy; este test cierra el de dentro de seis meses, cuando alguien escriba
 * {@code findByEmail} porque "es solo una consulta" y devuelva —o cuente— a un cliente de otra tienda.
 * Los correos, los slugs, los SKU y los códigos de cupón son únicos <b>por tienda</b>, no globales.
 *
 * <p>La regla, para todo repositorio cuya entidad tenga un campo {@code store}: cada método declarado
 * <ol>
 *   <li>menciona la tienda (en su nombre o en el de un parámetro), o</li>
 *   <li>recibe un {@code Long} que es un id ({@code id}, {@code customerId}, {@code companyId}…): se
 *       consulta por un registro concreto o por el de su padre, y quien lo tiene ya está en su tienda, o</li>
 *   <li>figura en {@link #GLOBAL_ON_PURPOSE} con la razón por la que es global.</li>
 * </ol>
 * Añadir una consulta global es posible, pero obliga a escribir aquí por qué es segura.
 */
class RepositoryTenantScopeTest {

    private static final String REPOSITORIES = "org.uvo.uvostore.repository";

    /**
     * Globales a propósito. Cada una es una clave que emite un tercero o un secreto aleatorio, único en
     * todo el sistema, o un trabajo que recorre todas las tiendas. Quien las usa para responder a una
     * petición tiene que comprobar la tienda de lo que encuentra (ver la razón de cada una).
     */
    private static final Map<String, String> GLOBAL_ON_PURPOSE = new TreeMap<>(Map.ofEntries(
            Map.entry("CustomerRepository.findByInvitationToken",
                    "token aleatorio UNIQUE; AuthController.acceptInvitation compara la tienda con la del host"),
            Map.entry("UserRepository.findByInvitationToken",
                    "token aleatorio UNIQUE; AuthController.adminAcceptInvitation compara la tienda con la del host"),
            Map.entry("UserRepository.findByPasswordResetToken",
                    "token aleatorio UNIQUE enviado por correo; hoy NO se compara con la tienda del host (anotado)"),
            Map.entry("OrderRepository.findByOrderNumber",
                    "número de orden aleatorio; OrderTrackingServiceImpl y MercadoPagoServiceImpl filtran por tienda"),
            Map.entry("OrderRepository.findByPaymentId",
                    "token de Webpay / referencia de MercadoPago; WebpayServiceImpl filtra por tienda"),
            Map.entry("OrderRepository.findByStripeCheckoutSessionId",
                    "id de sesión que emite Stripe; PaymentServiceImpl no filtra por tienda después (anotado)"),
            Map.entry("OrderRepository.findByStripePaymentIntentId",
                    "id de PaymentIntent que emite Stripe; PaymentServiceImpl no filtra por tienda después (anotado)"),
            Map.entry("OrderRepository.findPendingPaymentsToReconcile",
                    "trabajo programado: recorre todas las tiendas y fija el tenant por orden"),
            Map.entry("OrderRepository.findAbandonedPending",
                    "trabajo programado: recorre todas las tiendas y fija el tenant por orden"),
            Map.entry("OrderRepository.findPaidWithoutStockApplied",
                    "trabajo programado (StockRecoveryJob): recorre todas las tiendas y fija el tenant por orden"),
            Map.entry("OrderRepository.findProductsWithStockPending",
                    "conciliación POS-4: recibe ids de productos ya acotados (los mapeos de una empresa, de una "
                            + "tienda) y solo devuelve ids de esos mismos productos"),
            Map.entry("PosConnectionRepository.findAllActive",
                    "trabajo programado (PosReconciliationJob): recorre todas las tiendas; cada conexión trae la "
                            + "suya y la integración se comprueba por tienda"),
            Map.entry("OrderRepository.countPaidWithoutStockApplied",
                    "métrica PROD-15b (OperationalBacklogMetrics): un conteo de todo el sistema, sin la tienda como "
                            + "etiqueta, que solo lee /actuator/prometheus"),
            Map.entry("OrderRepository.countPendingCreatedBefore",
                    "métrica PROD-15b (OperationalBacklogMetrics): un conteo de todo el sistema")));

    @Test
    @DisplayName("Toda consulta de un repositorio de tienda está acotada o es una global justificada")
    void everyQueryOnATenantEntityIsScopedOrJustifiedGlobal() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Class<?> repository : tenantRepositories()) {
            for (String unscoped : unscopedMethods(repository)) {
                if (!GLOBAL_ON_PURPOSE.containsKey(unscoped)) {
                    offenders.add(unscoped);
                }
            }
        }

        assertThat(offenders)
                .as("Consultas sin tienda ni justificación. O reciben storeId, o se añaden a "
                        + "GLOBAL_ON_PURPOSE con la razón de por qué son seguras:")
                .isEmpty();
    }

    @Test
    @DisplayName("La lista de globales no guarda entradas muertas")
    void theAllowlistHasNoStaleEntries() throws Exception {
        List<String> found = new ArrayList<>();
        for (Class<?> repository : tenantRepositories()) {
            found.addAll(unscopedMethods(repository));
        }

        // Es también el control de que el recorrido ve repositorios reales: si no encontrara ninguno, el
        // test de arriba pasaría vacío y no probaría nada.
        assertThat(found).containsAll(GLOBAL_ON_PURPOSE.keySet());
    }

    @Test
    @DisplayName("La regla marca una consulta sin tienda y acepta las acotadas")
    void theRuleFlagsAnUnscopedQueryAndAcceptsScopedOnes() {
        // Prueba la regla misma, con repositorios de mentira, para no depender de tener que reintroducir
        // un hueco real en el código de producción solo para ver que el guarda muerde.
        assertThat(unscopedMethods(Unsafe.class)).containsExactlyInAnyOrder(
                "Unsafe.findByEmail", "Unsafe.countByCreatedAtAfter", "Unsafe.findByActiveTrue");

        assertThat(unscopedMethods(Safe.class)).isEmpty();
    }

    @Test
    @DisplayName("Los nombres de parámetro están disponibles (-parameters), que es de lo que depende la regla")
    void parameterNamesAreAvailable() throws Exception {
        Method method = Safe.class.getMethod("findByStoreIdAndEmail", Long.class, String.class);

        assertThat(method.getParameters()[0].isNamePresent()).isTrue();
        assertThat(method.getParameters()[0].getName()).isEqualTo("storeId");
    }

    // --- la regla ------------------------------------------------------------------------------------

    /** "Repositorio.método" de cada método declarado que no está acotado a una tienda. */
    private static List<String> unscopedMethods(Class<?> repository) {
        List<String> result = new ArrayList<>();
        for (Method method : repository.getDeclaredMethods()) {
            // Los sintéticos no son consultas escritas por nadie: `$jacocoInit` lo inyecta JaCoCo en las
            // interfaces, y `findById(Object)` es el método puente que javac genera cuando un repositorio
            // redeclara `findById(Long)` (CategoryRepository).
            if (method.isSynthetic() || method.isBridge()) {
                continue;
            }
            if (!isScoped(method)) {
                result.add(repository.getSimpleName() + "." + method.getName());
            }
        }
        return result;
    }

    private static boolean isScoped(Method method) {
        if (method.getName().contains("Store")) {
            return true;
        }
        for (Parameter parameter : method.getParameters()) {
            String name = parameter.getName();
            if (name.toLowerCase().contains("store")) {
                return true;
            }
            boolean isLong = parameter.getType() == Long.class || parameter.getType() == long.class;
            if (isLong && (name.equals("id") || name.endsWith("Id"))) {
                return true;
            }
        }
        return false;
    }

    // --- qué repositorios cuentan --------------------------------------------------------------------

    private static List<Class<?>> tenantRepositories() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));

        List<Class<?>> result = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(REPOSITORIES)) {
            Class<?> repository = Class.forName(definition.getBeanClassName());
            Class<?> entity = ResolvableType.forClass(repository).as(Repository.class).getGeneric(0).resolve();
            if (entity != null && hasStoreField(entity)) {
                result.add(repository);
            }
        }
        return result;
    }

    private static boolean hasStoreField(Class<?> entity) {
        for (Class<?> type = entity; type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getName().equals("store") && field.getType() == Store.class) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- repositorios de mentira, solo para probar la regla ------------------------------------------

    interface Unsafe extends JpaRepository<Customer, Long> {
        Optional<Customer> findByEmail(String email);

        long countByCreatedAtAfter(Instant since);

        List<Customer> findByActiveTrue();
    }

    interface Safe extends JpaRepository<Customer, Long> {
        Optional<Customer> findByStoreIdAndEmail(Long storeId, String email);

        long countByStoreId(Long storeId);

        List<Customer> findByParentId(Long parentId);

        Optional<Customer> findById(Long id);
    }
}
