import re
path = r'C:\Users\Admin\AndroidStudioProjects\HomeSync\app\src\main\java\com\homesync\app\util\FirebaseSyncManager.kt'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

text = text.replace(
    ".set(payload, SetOptions.merge())\n                .addOnSuccessListener {\n                }",
    ".set(payload, SetOptions.merge())\n                .addOnSuccessListener {\n                    android.util.Log.i(\"HomeSyncScreenTime\", \"FIRESTORE_WRITE_SUCCESS path=hs_screentime/$cleanCode\")\n                }\n                .addOnFailureListener { e ->\n                    android.util.Log.e(\"HomeSyncScreenTime\", \"FIRESTORE_WRITE_ERROR path=hs_screentime/$cleanCode\", e)\n                }"
)
with open(path, 'w', encoding='utf-8') as f:
    f.write(text)
