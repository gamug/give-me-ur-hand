# give-me-ur-hand — Diseño MVP

**Fecha:** 2026-08-14
**Repo:** https://github.com/gamug/give-me-ur-hand
**Estado:** Aprobado por el usuario, pendiente de plan de implementación.

## Contexto y objetivo

App de apoyo para personas en zona roja tras un terremoto. El MVP es un chatbot de
primeros auxilios psicológicos (agente con RAG contra contenido curado) más un
lobby que anticipa futuras funciones. Cuando alguien necesita ayuda humana real,
el sistema lo conecta con un profesional disponible, repartiendo la carga entre
varios profesionales en vez de saturar a uno solo.

## Alcance del MVP

Incluido:
- Backend Kotlin/Ktor que expone el pipeline del agente, el RAG y la coordinación
  de profesionales.
- App Android (Kotlin + Jetpack Compose): lobby, chat de apoyo psicológico, y
  login + dashboard para profesionales.
- Script de ingesta que convierte los PDFs de `psicological-first-aid/` en chunks
  indexados en MongoDB Atlas.
- Script de seed para dar de alta profesionales.

Fuera de alcance (queda como "Próximamente" en el lobby, sin funcionalidad real):
Reporta tu estado / Estoy bien, Directorio de contactos de emergencia, Mapa de
refugios/albergues, Recursos y guías descargables, Chequeo de integridad
estructural de construcciones.

## Arquitectura

```
give-me-ur-hand/
├── backend/    Kotlin + Ktor — agente, RAG, coordinación de profesionales, API REST
├── android/    Kotlin + Jetpack Compose — frontend (víctimas + profesionales)
├── psicological-first-aid/   PDFs fuente para el RAG (ya provistos por el usuario)
└── docs/
```

- El backend concentra todos los secretos (API key de DeepSeek, connection string
  de Mongo, JWT secret) **exclusivamente como variables de entorno**. Nunca se
  hardcodean ni se versionan.
- La app Android solo habla HTTP con el backend; nunca toca Mongo ni DeepSeek
  directamente.
- Dos proyectos Gradle independientes (sin multi-módulo compartido) para mantener
  el MVP simple.

## Pipeline del agente (`POST /chat`)

Entrada: `{ sessionId: string (UUID generado por el cliente), message: string }`.

1. **Estandarizar**: llamada a DeepSeek para corregir ortografía/gramática del
   mensaje del usuario → texto limpio.
2. **Clasificar intención**: llamada a DeepSeek sobre el texto limpio, clasifica en:
   - `ayuda_humana_explicita` — el usuario pide hablar con una persona.
   - `riesgo_de_crisis` — señales de daño a sí mismo/otros o peligro inmediato.
   - `pregunta_normal` — cualquier otro caso.
   - Regla de seguridad: tanto `ayuda_humana_explicita` como `riesgo_de_crisis`
     disparan el flujo de ayuda humana (paso 5), aunque el usuario no la haya
     pedido explícitamente en el segundo caso.
3. **Expandir la consulta** (solo si `pregunta_normal`): DeepSeek genera 3
   reformulaciones del texto limpio con una perspectiva más amplia.
4. **RAG**: se ejecuta `$search` (Mongo Atlas Search, texto completo) contra la
   colección `knowledge_chunks` con las 3 reformulaciones + el texto original.
   Se deduplican los resultados por `_id` y se toman los 6 de mejor score.
5. **Resolución**:
   - `ayuda_humana_explicita` / `riesgo_de_crisis` → se ejecuta el algoritmo de
     asignación de profesional (ver más abajo) y se responde con un mensaje
     empático en español + el número del profesional asignado.
   - `pregunta_normal` con chunks → DeepSeek redacta la respuesta final en
     español, basada **solo** en el contenido de los chunks recuperados (los
     chunks pueden estar en inglés; el modelo traduce/sintetiza al responder).
     El prompt de síntesis instruye tono calmado, psicoeducativo, sin
     diagnosticar, y a sugerir ayuda profesional cuando sea pertinente.
   - `pregunta_normal` sin chunks → respuesta fija: "Tu pregunta no está
     relacionada con el propósito de esta aplicación."

Salida: `{ reply: string, kind: "answer" | "human_help" | "out_of_scope" | "error" }`.
El valor `"error"` se usa exclusivamente para fallas técnicas (DeepSeek/Mongo
no responden tras el reintento) y nunca debe confundirse con `"out_of_scope"`
(ver sección de Manejo de errores).

## Ingesta de contenido RAG

Script (`backend`, ejecución manual única, no expuesto por HTTP) que:
1. Lee cada PDF de `psicological-first-aid/` con Apache PDFBox.
2. Extrae texto por página y lo parte en chunks de ~600 caracteres con solape
   (~100 caracteres) respetando límites de oración cuando es posible.
3. Inserta cada chunk en `knowledge_chunks` con metadata:
   `{ text, sourceDocument, page, chunkIndex, language: "en", createdAt }`.
4. Requiere que exista un índice Atlas Search (analizador estándar) sobre el
   campo `text` de `knowledge_chunks` — se documenta cómo crearlo desde el
   panel de Atlas (no se puede crear vía driver en el free tier).

Ruta de los PDFs configurable vía argumento o env var `PFA_SOURCE_DIR`
(default `../psicological-first-aid`).

## Coordinación de profesionales

### Modelo de datos

`professionals`:
```
{ _id, name, phone, username, passwordHash (bcrypt), active: Boolean, createdAt }
```

`assignments`:
```
{ _id, professionalId, sessionId, reasonSnippet, status: "active"|"closed",
  assignedAt, closedAt: Date? }
```

### Algoritmo de asignación

1. Obtener profesionales con `active = true`.
2. Para cada uno, contar sus `assignments` con `status = "active"` **y**
   `assignedAt` dentro de las últimas `ASSIGNMENT_MAX_AGE_HOURS` horas (env var,
   default 4) — esto trata como cerrados automáticamente los casos que el
   profesional olvidó cerrar.
3. Elegir el profesional con menor conteo; empate → el que tenga `assignedAt`
   más antiguo entre sus asignaciones activas (o ninguna asignación, si nunca
   ha recibido un caso).
4. Crear un registro en `assignments` con `status = "active"`.
5. Si no hay profesionales activos, no se crea asignación y se usa
   `FALLBACK_HELP_PHONE` (default `+57 3219699131`) directamente en la
   respuesta.

### Alta de profesionales

Script de seed (backend, ejecución manual) que lee un archivo local
`professionals-seed.json` (no versionado, se agrega a `.gitignore` por contener
contraseñas en texto plano antes de hashear) con `{ name, phone, username,
password }[]`, hashea la contraseña con bcrypt e inserta en `professionals`.

### Dashboard de profesionales (dentro de la app Android)

- Acceso: link discreto "Acceso profesionales" en el Lobby → pantalla de login
  (usuario/contraseña) → `POST /professionals/login` → JWT (firmado con
  `JWT_SECRET`, expiración 12h).
- `GET /professionals/me/cases` (requiere JWT) → lista de asignaciones del
  profesional autenticado (activas primero, luego cerradas recientes), cada una
  con fecha, `reasonSnippet` y estado.
- `POST /professionals/cases/{id}/close` (requiere JWT, valida que el caso
  pertenezca al profesional autenticado) → marca `status = "closed"`,
  `closedAt = now`.
- Pantalla Compose con lista de tarjetas por caso y botón "Marcar como
  atendido" en los casos activos.

## App Android — estructura

Jetpack Compose + Material 3. Paleta cálida y calmada (verdes agua, blancos
cálidos, tipografía redondeada), sin autenticación para el flujo de víctimas.

Pantallas:
- **Lobby**: grid de tiles. "Chat de apoyo psicológico" (única activa) navega al
  chat. Las otras cinco (Reporta tu estado / Estoy bien, Directorio de
  contactos de emergencia, Mapa de refugios/albergues, Recursos y guías
  descargables, Chequeo de integridad estructural) se muestran deshabilitadas
  con etiqueta "Próximamente". Link discreto "Acceso profesionales" al pie.
- **Chat**: burbujas de conversación (usuario/agente), indicador de
  "escribiendo...", campo de texto. Genera y persiste localmente (DataStore) un
  `sessionId` UUID por instalación, se envía en cada request a `/chat`.
- **Login profesional**: usuario + contraseña.
- **Dashboard profesional**: lista de casos asignados, acción de cerrar caso,
  logout.

Networking: Ktor Client (o Retrofit) apuntando a la URL base del backend
(configurable en build, no hardcodeada en código fuente — `BuildConfig` desde
`local.properties`/variable de entorno de build, no es secreto de runtime).

## Variables de entorno (backend)

```
DEEPSEEK_API_KEY
DEEPSEEK_BASE_URL          default: https://api.deepseek.com
DEEPSEEK_MODEL             default: deepseek-chat
MONGODB_URI
MONGODB_DATABASE           default: give_me_ur_hand
JWT_SECRET
FALLBACK_HELP_PHONE        default: +57 3219699131
ASSIGNMENT_MAX_AGE_HOURS   default: 4
```

(El `.env` del usuario ya trae `DEEPSEEK_API_KEY`, `DEEPSEEK_MODEL`,
`MONGODB_URI`, `MONGODB_DATABASE`; se corrige el nombre `DEEPSEEK_BASE_UR` →
`DEEPSEEK_BASE_URL`; faltan `JWT_SECRET`, `FALLBACK_HELP_PHONE` y
`ASSIGNMENT_MAX_AGE_HOURS`, que se agregan con valores por defecto/placeholder.)

## Manejo de errores

- Falla la llamada a DeepSeek (cualquier paso) → un reintento; si vuelve a
  fallar, responder con mensaje técnico genérico en español (nunca se confunde
  con la respuesta fija de "fuera de alcance").
- Falla la búsqueda en Mongo → mismo mensaje técnico genérico; nunca se asume
  silenciosamente "sin chunks".
- Falla la asignación de profesional (Mongo caído, etc.) → usar
  `FALLBACK_HELP_PHONE` directamente.
- Login profesional inválido → 401 genérico, sin indicar si el usuario existe.

## Testing

- **Backend**: tests unitarios de los prompts/parseo de cada paso del agente
  (cliente DeepSeek mockeado vía interfaz), del merge/dedupe de chunks del RAG,
  y del algoritmo de asignación (repositorios en memoria fake, sin Mongo real).
- **Android**: tests de ViewModel con fakes del cliente HTTP para chat, login y
  dashboard.
- No se usa Testcontainers ni Mongo embebido para el MVP — se prioriza
  velocidad de entrega; las pruebas de integración reales contra Atlas quedan
  como verificación manual antes de entregar.

## Riesgos / seguimiento fuera de alcance del MVP

- No hay cifrado adicional ni consentimiento explícito sobre el `reasonSnippet`
  guardado para el profesional — aceptable para el MVP dado el contexto de
  emergencia, pero debe revisarse antes de escalar el uso.
- El analizador de Atlas Search es estándar (inglés); si más adelante se cargan
  fuentes en español convendría un índice/analyzer dedicado.
- No hay reintentos ni cola de mensajes si DeepSeek está caído por tiempo
  prolongado — solo un reintento simple.
