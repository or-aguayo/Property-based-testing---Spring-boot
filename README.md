# Property-based Testing for Banking API

Se añadieron pruebas basadas en propiedades para cubrir los invariantes clave del dominio descrito. A continuación se resume lo que se valida automáticamente:

## 1) Cuentas y saldos

- **1.1 Depósito aumenta saldo y no lo hace negativo.** Genera cuentas con saldo inicial ≥ 0 y depósitos en rangos pequeños/medios/grandes. Oráculo: saldo final = saldo inicial + monto (con redondeo) y saldo_final ≥ 0.
- **1.2 Retiro nunca deja saldo negativo.** Se prueban retiros arbitrarios respetando el oráculo de éxito/fracaso.
- **1.3 Consulta es idempotente.** Múltiples lecturas consecutivas retornan el mismo saldo.
- **1.4 Limitación de escala/precisión monetaria.** Depósitos y retiros con 0…6 decimales se redondean consistente al modelo de dos decimales.

## 2) Transferencias

- **2.1 Conservación de dinero.** La suma de saldos entre cuentas fuente y destino no cambia tras transferencias exitosas.
- **2.2 No negatividad en ambas cuentas.** Tras transferencias válidas ambos saldos permanecen ≥ 0; si falla, nada cambia.
- **2.3 Idempotencia con idempotency-key.** Reintentos con la misma clave aplican el efecto a lo sumo una vez.

## 3) Propiedades metamórficas

- **3.1 Conmutatividad de depósitos.** Cualquier orden de depósitos da el mismo saldo final.
- **3.2 “Depositar y retirar” vs “retirar y depositar”.** Las secuencias equivalentes producen el saldo esperado según reglas de negocio.
- **3.3 Transferencias reversibles.** Transferir A→B y luego B→A restaura saldos cuando la operación es válida.

## 4) Pruebas stateful (secuencias)

- **4.1 Secuencias aleatorias mantienen invariantes globales.** Se generan combinaciones aleatorias de depósitos, retiros y transferencias garantizando no negatividad y conservación del total.
- **4.2 Exploración por intercalación.** Permutaciones de operaciones cortas mantienen los invariantes verificados paso a paso.

## 5) Persistencia e historial

- **5.1 Crece el historial sólo en éxito.** Las operaciones fallidas no alteran el conteo de transacciones.
- **5.2 Unicidad de IDs y orden temporal.** Los identificadores son únicos y el orden recuperado respeta la política temporal.
- **5.3 Round-trip guardar/leer.** Persistir y luego recuperar cuentas produce igualdad estructural de valores.

## 6) API REST/contratos

- **6.1 POST→GET coherente.** Después de crear una cuenta por la API, la lectura refleja el mismo estado.
- **6.2 Transferencia conserva suma reportada.** La suma de saldos reportada por la API antes/después de transferir se mantiene.
- **6.3 Validación de entrada.** Payloads inválidos retornan 4xx y no modifican el estado.

## 7) Concurrencia y consistencia

- **7.1 Depósitos concurrentes no pierden actualizaciones.** La suma de depósitos concurrentes se refleja en el saldo final.
- **7.2 Transferencias cruzadas concurrentes.** No hay saldos negativos y el total combinado se conserva.

## 13) Propiedades globales del sistema

- **13.1 Invariante de no-negatividad global.** Ninguna cuenta queda en negativo tras secuencias válidas/ inválidas.
- **13.2 Conservación contable.** El total del sistema respeta total_inicial + Σ(deps) − Σ(retiros).

> Nota: Los ítems 8–12 requieren políticas de negocio, resiliencia o métricas adicionales no presentes en esta versión de la API y quedan como trabajo futuro.
