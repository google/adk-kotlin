# Skills

> Internal. Part of the ADK Kotlin specification: the repo-root `SPEC.md` is the
> charter and index, and `DECISIONS.md` holds the `[D-n]` design decisions. Not
> exported to GitHub.

*Package `com.google.adk.kt.skills`.*

A skill is a self-contained bundle described by a `SKILL.md` file: a YAML
frontmatter block (name, description, and other metadata) followed by a
free-form Markdown body that holds the skill's instructions. Alongside
`SKILL.md`, a skill may carry resource directories named `references`, `assets`,
and `scripts`. A `SkillSource` loads these skills from some backing store (a
filesystem or Android assets).

## Frontmatter

Parsed and validated representation of a `SKILL.md` YAML header. Validation runs
at construction.

```kotlin
data class Frontmatter(
  val name: String,
  val description: String,
  val license: String? = null,
  val compatibility: String? = null,
  val allowedTools: String? = null,
  val metadata: Map<String, Any?> = emptyMap(),
)
// init validates: name length 1..64, no leading/trailing/consecutive hyphens, chars [a-z0-9-];
// description length 1..1024; compatibility <= 500 chars.
```

## SkillSource

Interface for loading skills and their resources from a backing store. Every
method is `suspend` and returns a `Result` so I/O and parsing failures surface
as failed results rather than thrown exceptions. Resource directories are
limited to `references`, `assets`, and `scripts`.

```kotlin
interface SkillSource {
  companion object {
    const val DIR_REFERENCES = "references"
    const val DIR_ASSETS = "assets"
    const val DIR_SCRIPTS = "scripts"
    val VALID_RESOURCE_DIRS = listOf(DIR_REFERENCES, DIR_ASSETS, DIR_SCRIPTS)
  }

  suspend fun listFrontmatters(): Result<List<Frontmatter>>
  suspend fun listResources(skillName: String, resourceDirectoryPath: String): Result<List<String>>
  suspend fun loadFrontmatter(skillName: String): Result<Frontmatter>
  suspend fun loadInstructions(skillName: String): Result<String>
  suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray>
}
```

## SkillSourceException

Exception type used to describe a skill-loading failure carried in a failed
`Result`.

```kotlin
class SkillSourceException(message: String, cause: Throwable? = null) : Exception(message, cause)
```

## NewFileSystemSource

`SkillSource` (commonJvmAndroidMain) that loads skills from a base directory on
the local filesystem.

```kotlin
// Constructor param is `private val` - not exposed as a public property.
class NewFileSystemSource(private val skillsBaseDir: String) : SkillSource {
  override suspend fun listFrontmatters(): Result<List<Frontmatter>>
  override suspend fun listResources(skillName: String, resourceDirectoryPath: String): Result<List<String>>
  override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter>
  override suspend fun loadInstructions(skillName: String): Result<String>
  override suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray>
}
```

## AssetSkillSource

`SkillSource` (androidMain) that loads skills from the Android `AssetManager`.
The primary constructor is `internal`; instances are built through the companion
`fromContext`.

```kotlin
// PRIMARY CONSTRUCTOR IS `internal` - not public. Build via companion `fromContext`.
class AssetSkillSource
internal constructor(private val assets: AssetManager, private val skillsBaseDir: String) :
  SkillSource {

  override suspend fun listFrontmatters(): Result<List<Frontmatter>>
  override suspend fun listResources(skillName: String, resourceDirectoryPath: String): Result<List<String>>
  override suspend fun loadFrontmatter(skillName: String): Result<Frontmatter>
  override suspend fun loadInstructions(skillName: String): Result<String>
  override suspend fun loadResource(skillName: String, resourcePath: String): Result<ByteArray>

  companion object {
    fun fromContext(context: Context, skillsBaseDir: String): AssetSkillSource
  }
}
```

## Internal (not public API)

-   `SkillMdParsing.kt` (commonJvmAndroidMain) is entirely internal: the
    `SKILL_FILE_NAME` and `FRONTMATTER_SEPARATOR` constants and the parsing
    helpers `sourceRunCatching`, `parseSkillMdContent`,
    `buildValidatedFrontmatter`, and `parseSkillMd`.
-   File-private helpers in `NewFileSystemSource.kt` (`parseSkillFromDir`) and
    `AssetSkillSource.kt` (`joinAssetPath`, `normalizeRelativePath`,
    `firstSegment`).
