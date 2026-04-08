#!/bin/bash
# Hook: checks if edited file is a flow-related Java class
# Returns JSON with additionalContext if update is needed

FILE=$(jq -r '.tool_input.file_path // .tool_response.filePath // ""' 2>/dev/null)

if echo "$FILE" | grep -qE 'src/main/java/com/aicomm/.*(Service|Consumer|Controller|Handler|Executor|Tool|Classifier|Factory)\.java$'; then
  printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"FLOW DOC REMINDER: A flow-related file was changed. When done with current task, run /update-flow-docs to sync FLOW.md and FLOW_DIAGRAM.md."}}'
fi
