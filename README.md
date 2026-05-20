# AI Assistant Chat History Merge

Utilities for merging JetBrains IDE AI Assistant chat workspace data from an older workspace directory into a newer one.

This project is useful when you have an old JetBrains IDE workspace that contains AI Assistant chat history and a newer workspace that you want to continue using, while preserving or restoring the previous AI chat data.

## Requirements

This project uses [Babashka](https://babashka.org/) to run the merge script.

Install Babashka by following the official installation instructions:

<https://github.com/babashka/babashka#installation>

## Usage

Run the merge script with:

```bash
./merge-ai-chats.bb MyProject workspace.old workspace
```

Where:

- `MyProject` is the project name to look for in the workspace XML files.
- `workspace.old` is the old workspace directory containing the AI chat data you want to merge.
- `workspace` is the current or newer workspace directory.

The script writes the merged workspace data to:

```text
./merged
```


## Finding your JetBrains workspace directory

JetBrains IDEs store user-specific data in product- and version-specific configuration directories.

Default configuration directory locations are:

### Windows

  ```text
  %APPDATA%\JetBrains\<product><version>
  ```

  Example:

  ```text
  C:\Users\JohnS\AppData\Roaming\JetBrains\IntelliJIdea2026.1
  ```

- **macOS**

  ```text
  ~/Library/Application Support/JetBrains/<product><version>
  ```

  Example:

  ```text
  ~/Library/Application Support/JetBrains/IntelliJIdea2026.1
  ```

- **Linux**

  ```text
  ~/.config/JetBrains/<product><version>
  ```

  Example:

  ```text
  ~/.config/JetBrains/IntelliJIdea2026.1
  ```

See the JetBrains documentation on IDE directories for more details: <https://www.jetbrains.com/help/idea/2026.1/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html#config-directory>

The workspace directory is typically inside that product/version directory:

```text
<JetBrains config directory>/workspace
```

For example, on Linux with IntelliJ IDEA 2026.1:

```text
~/.config/JetBrains/IntelliJIdea2026.1/workspace
```

For more details, see the JetBrains documentation:

<https://www.jetbrains.com/help/idea/2026.1/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html#config-directory>

## Examples with full paths

### Linux

```bash
./merge-ai-chats.bb MyProject \
  ~/.config/JetBrains/IntelliJIdea2026.1/workspace.old \
  ~/.config/JetBrains/IntelliJIdea2026.1/workspace
```

### macOS

```bash
./merge-ai-chats.bb MyProject \
  "$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1/workspace.old" \
  "$HOME/Library/Application Support/JetBrains/IntelliJIdea2026.1/workspace"
```

### Windows

```bash
./merge-ai-chats.bb MyProject \
  "$APPDATA/JetBrains/IntelliJIdea2026.1/workspace.old" \
  "$APPDATA/JetBrains/IntelliJIdea2026.1/workspace"
```

Adjust `IntelliJIdea2026.1` to match your JetBrains IDE product and version, for example `PyCharm2026.1`, `WebStorm2026.1`, or another installed IDE directory.

## Suggested workflow

1. Quit the IDE completely.

2. Run the merge script:

   ```bash
   ./merge-ai-chats.bb MyProject workspace.old workspace
   ```

3. Move the current workspace aside as a backup:

   ```bash
   mv workspace workspace.orig
   ```

4. Move the merged workspace into place:

   ```bash
   mv merged workspace
   ```

5. Start the IDE again.

The IDE should now open using the merged workspace data.

## Important note

Use this tool at your own risk.

JetBrains workspace internals are not documented for this use case, so this project is based on observation and guesswork. It appears to work, but you should always back up your original workspace and settings directories before replacing anything.

## Suggested workflow

1. Quit the IDE completely.
2. Move the existing old workspace out of the way, for example:

   ```bash
   mv workspace workspace.orig
   ```

3. Move the merged workspace into place:

   ```bash
   mv merged workspace
   ```

4. Start the IDE again.

The IDE should now open using the merged workspace data.
