# TODO-07: Symlink Following in Recursive Delete

**Severity:** HIGH
**Component:** Storage
**File:** `src/main/kotlin/com/example/storage/FileSystemStorage.kt:119-131`

## Description

Directory deletion uses `Files.walkFileTree()` which could follow symlinks and delete files outside the intended base directory:

```kotlin
if (resolvedPath.isDirectory()) {
    Files.walkFileTree(resolvedPath, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
```

## Impact

- If a directory contains a symlink to `/etc` or `/home`, the delete operation follows the symlink and deletes real files outside the base directory
- Could lead to system damage or data loss

## Suggested Fix

Check for symlinks before following them during traversal:

```kotlin
override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
    if (Files.isSymbolicLink(file)) {
        Files.delete(file) // Delete the symlink itself, not the target
    } else if (file.toAbsolutePath().normalize().startsWith(basePathNormalized)) {
        Files.delete(file)
    }
    return FileVisitResult.CONTINUE
}
```

Or use `EnumSet.noneOf(FileVisitOption::class.java)` explicitly to document that symlinks are not followed (this is the default, but making it explicit improves clarity).

## Affected Tests

- `FileSystemStorageTest` — add test with symlink in directory to delete
