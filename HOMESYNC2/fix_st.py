import re
path = r'C:\Users\Admin\AndroidStudioProjects\HomeSync\app\src\main\java\com\homesync\app\util\ScreenTimeManager.kt'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace(
    "fun updateUsageFromChild(context: Context, childId: String, actualUsedSeconds: Int) {\n        val cleanId = cleanChildId(childId)\n        if (cleanId.isBlank()) return",
    "fun updateUsageFromChild(context: Context, childId: String, actualUsedSeconds: Int) {\n        val cleanId = cleanChildId(childId)\n        if (cleanId.isBlank()) return\n        android.util.Log.i(\"HomeSyncScreenTime\", \"UPDATING_USAGE_FROM_CHILD childCode=$cleanId used=$actualUsedSeconds\")"
)
with open(path, 'w', encoding='utf-8') as f:
    f.write(text)
