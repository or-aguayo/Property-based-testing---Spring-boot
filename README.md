# Property-based Testing for Banking API

Este proyecto de ejemplo incorpora **pruebas basadas en propiedades** a una API bancaria escrita con Spring Boot. Las propiedades se ejecutan con [jqwik](https://jqwik.net/) sobre JUnit 5, lo que permite generar automáticamente cientos de escenarios distintos sin escribir cada caso a mano. Aquí se documenta qué se probó, cómo se modelaron esas pruebas y cómo puede ejecutarlas cualquier persona incluso si nunca ha usado property-based testing.

## Cómo ejecutar las pruebas

1. Instala un JDK 17+ (el wrapper de Maven viene incluido).
2. Desde la raíz del repositorio ejecuta:

   ```bash
   ./mvnw test
   ```

   El comando lanza tanto los tests clásicos de JUnit como las propiedades de jqwik. Cada propiedad define cuántos intentos aleatorios quiere ejecutar (`tries = 20`, `tries = 50`, etc.) y Maven mostrará estadísticas si alguna falla.

## Cómo están escritas las pruebas basadas en propiedades

Las propiedades viven en dos clases principales:

* `AccountServicePropertyTest`: ejercita directamente la capa de dominio/servicio con los *beans* reales de Spring.
* `AccountApiPropertyTest`: utiliza `MockMvc` para probar el contrato HTTP real de la API, incluyendo serialización JSON.

Ambas clases usan las anotaciones que jqwik añade a JUnit:

* `@Property`: marca un método como propiedad. jqwik lo ejecuta muchas veces cambiando sus entradas.
* `@ForAll`: indica que un parámetro recibirá valores generados automáticamente.
* `@Provide`: expone un método que devuelve un generador reutilizable (`Arbitrary<T>`). jqwik lo invoca para obtener datos como saldos o números de cuenta.

En Java, un generador (`Arbitrary`) es responsable de crear valores aleatorios. Por ejemplo, `nonNegativeBalances()` produce cantidades monetarias aleatorias en el rango deseado, respetando la escala de dos decimales que espera el dominio. Al anotar el método con `@Provide`, jqwik lo registra y cualquier propiedad puede pedirlo referenciándolo por su nombre.

### ¿Por qué usamos `BigDecimal`?

`BigDecimal` es el tipo de Java que permite representar números decimales sin los errores de redondeo que aparecen con `double` o `float`. Resulta imprescindible para manejar dinero. Todas las propiedades normalizan los resultados a dos decimales (modo `HALF_UP`) para reflejar cómo la aplicación redondea los saldos reales.

### Ejemplo de helper: `fetchBalance`

El método auxiliar

```java
private BigDecimal fetchBalance(String accountNumber)
```

vive en `AccountApiPropertyTest`. Hace una llamada HTTP `GET /api/accounts/{accountNumber}`, espera un `200 OK`, parsea la respuesta JSON con `ObjectMapper` y extrae el campo `balance` como `BigDecimal`. Se usa como oráculo para comprobar que las operaciones de la API REST devuelven los saldos consistentes tras depósitos, retiros o transferencias.

## Qué se probó exactamente

Las propiedades cubren los invariantes más importantes del dominio. A continuación se detalla cada bloque con el método que lo implementa y la idea de prueba:

| Bloque | Propiedad | Método | Qué comprueba y cómo |
| --- | --- | --- | --- |
| 1) Cuentas y saldos | Depósitos crecen el saldo y nunca lo hacen negativo | `depositAlwaysIncreasesBalance` | Crea cuentas con saldos arbitrarios (`@ForAll("nonNegativeBalances")`), realiza un depósito generado (`positiveAmounts`) y verifica que el saldo final coincide con el modelo matemático `saldo inicial + depósito`.
| | Retiros respetan fondos disponibles | `withdrawalsRespectAvailableBalance` | Ejecuta retiros aleatorios. Si hay fondos suficientes el saldo disminuye; si no, espera una `BusinessException` y que el saldo permanezca igual.
| | Consultar saldo es idempotente | `balanceLookupIsIdempotent` | Lee dos veces el mismo saldo para confirmar que no cambia el estado.
| | La escala monetaria siempre es de dos decimales | `monetaryScaleIsConsistent` | Genera montos con 4–6 decimales (`variableScaleAmounts`) y comprueba que el servicio redondea a dos decimales en depósitos y retiros.
| 2) Transferencias | Conservación del dinero | `transferConservesTotal` | Antes y después de cada transferencia valida que la suma de saldos entre cuenta origen y destino permanece inalterada, tanto si la operación tiene éxito como si se lanza una excepción.
| | No negatividad | `transferNeverCreatesNegativeBalances` | Asegura que ninguna transferencia exitosa deja cuentas en negativo y que los saldos originales se conservan cuando la operación es rechazada.
| | Idempotencia con `idempotency-key` | `transferHonoursIdempotencyKey` | Repite una transferencia con la misma clave y comprueba que el segundo intento no aplica cambios adicionales.
| 3) Propiedades metamórficas | Conmutatividad de depósitos | `depositOrderDoesNotMatter` | Genera secuencias de depósitos, las ejecuta en distintos órdenes y contrasta que el saldo final coincide en todos los casos.
| | Depositar y retirar vs. retirar y depositar | `depositWithdrawSequencesBehaveAsExpected` | Analiza secuencias equivalentes y verifica que llevan al mismo saldo, considerando retiros que pueden fallar.
| | Transferencias reversibles | `transferIsReversible` | Comprueba que transferir A→B y luego B→A deja los saldos como al inicio siempre que ambas operaciones sean válidas.
| 4) Pruebas stateful | Secuencias aleatorias mantienen invariantes | `randomSequencesMaintainInvariants` | Ejecuta listas aleatorias de depósitos, retiros y transferencias, contrastando después de cada paso que no existan saldos negativos y que el total contable coincida con el modelo interno.
| | Permutaciones respetan invariantes | `permutationsMaintainInvariants` | Evalúa permutaciones de operaciones cortas para asegurar que los invariantes se mantienen sin importar el orden.
| 5) Persistencia e historial | Historial solo crece en operaciones exitosas | `transactionHistoryGrowsOnlyOnSuccess` | Cuenta las transacciones persistidas y confirma que los intentos fallidos no aumentan el historial.
| | IDs únicos y orden temporal | `transactionsAreStoredWithUniqueIds` | Revisa que los identificadores sean únicos y el listado se recupere ordenado por fecha descendente.
| | Round-trip de persistencia | `accountPersistenceRoundTrip` | Guarda y vuelve a cargar una cuenta para demostrar que los datos se mantienen intactos.
| 6) API REST | POST seguido de GET es consistente | `postThenGetMatchesState` | Utiliza `MockMvc` para crear una cuenta vía HTTP y luego consultar que el JSON devuelto refleja exactamente el mismo estado.
| | La API conserva la suma reportada tras transferir | `transferKeepsReportedTotals` | Realiza una transferencia con `TransferRequest` y usa `fetchBalance` para verificar que la suma de saldos reportada por la API no cambia.
| | Validación de entrada | `invalidPayloadsAreRejected` | Genera números de cuenta y montos inválidos para comprobar que la API responde con códigos 4xx y no altera el sistema.
| 7) Concurrencia | Depósitos concurrentes sin pérdida | `concurrentDepositsDoNotLoseUpdates` | Lanza depósitos en paralelo con `CompletableFuture` y verifica que el saldo final coincide con la suma matemática.
| | Transferencias cruzadas concurrentes | `concurrentCrossTransfersConserveTotals` | Ejecuta transferencias simultáneas en ambas direcciones y comprueba que la suma total sigue siendo 2000 y que no hay saldos negativos.
| 13) Invariantes globales | Conservación contable y no negatividad global | `globalInvariantsHold` | Evalúa secuencias largas y registra estadísticas (`Statistics.label("total")`) para confirmar que la contabilidad global permanece consistente.

Además de las propiedades anteriores, el repositorio mantiene tests unitarios tradicionales en `AccountServiceTest` (para ilustrar la diferencia con property-based testing) y un `contextLoads` en `AppApplicationTests` que asegura que la aplicación Spring arranca correctamente.

## Cómo interpretar los resultados

* Si jqwik encuentra un contraejemplo reduce automáticamente los datos al caso más pequeño posible y lo muestra en consola, permitiendo reproducirlo.
* Las etiquetas `@Label` aparecen en la salida del build para identificar rápidamente qué propiedad falló.
* Las estadísticas recogidas con `Statistics.label(...)` facilitan detectar tendencias (por ejemplo, el total del sistema tras una secuencia larga).

## Próximos pasos

Los ítems 8–12 de la numeración original aluden a políticas de negocio no implementadas (fraude, límites regulatorios, etc.). Permanecen como trabajo futuro y servirían para añadir nuevas propiedades una vez que la funcionalidad exista.
