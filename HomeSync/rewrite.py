import re
path = r'C:\Users\Admin\AndroidStudioProjects\HomeSync\app\src\main\java\com\homesync\app\util\FirebaseRealtimeSyncManager.kt'
with open(path, 'r', encoding='utf-8') as f:
    text = f.read()

replacement = """    fun listenScreenTimeWithCommandDetails(
        childCode: String,
        onUpdate: (
            remainingSeconds: Int,
            isLocked: Boolean,
            totalAllowance: Int,
            usedSeconds: Int,
            commandId: String,
            commandTimestamp: Long,
            curfewOverride: Boolean,
            targetChildId: String,
            commandType: String,
            date: String
        ) -> Unit
    ): (() -> Unit)? {
        val cleanCode = childCode.trim().uppercase()
        if (cleanCode.isBlank()) return null
        val db = FirebaseSyncManager.getDb() ?: return null

        val rtdbPath = "hs_screentime/$cleanCode"
        android.util.Log.i("HomeSyncScreenTime", "SCREEN_TIME_FIRESTORE_PATH path=$rtdbPath")

        var isDisposed = false
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        val registration = db.collection("hs_screentime").document(cleanCode)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    android.util.Log.e("HomeSyncScreenTime", "SCREEN_TIME_FIRESTORE_ERROR message=${error.message}", error)
                    return@addSnapshotListener
                }
                if (isDisposed || snapshot == null || !snapshot.exists()) return@addSnapshotListener

                val remoteLock = snapshot.getBoolean("isLocked") ?: false
                val remoteAllowance = (snapshot.getLong("totalAllowance") ?: 21600L).toInt()
                val rawRem = (snapshot.getLong("remainingSeconds") ?: 21600L).toInt()
                val rawUsed = (snapshot.getLong("usedSeconds") ?: (remoteAllowance - rawRem).coerceAtLeast(0).toLong()).toInt()

                val cmdId = snapshot.getString("commandId") ?: "NONE"
                val cmdTimestamp = snapshot.getLong("commandTimestamp") ?: 0L
                val curfewOverride = snapshot.getBoolean("curfewOverride") ?: false
                val targetChildId = snapshot.getString("targetChildId") ?: cleanCode
                val commandType = snapshot.getString("commandType") ?: "NONE"
                val date = snapshot.getString("date") ?: ScreenTimeManager.getCurrentScreenTimeDate()
                val today = ScreenTimeManager.getCurrentScreenTimeDate()

                val isPastDate = date.isNotBlank() && date != today
                val used = if (isPastDate) 0 else rawUsed
                val rem = if (isPastDate) remoteAllowance else rawRem
                val recvTs = System.currentTimeMillis()

                android.util.Log.i("HomeSyncLatency", "COMMAND_RECEIVED childCode=$cleanCode targetChildId=$targetChildId commandType=$commandType commandId=$cmdId cmdTimestamp=$cmdTimestamp recvTimestamp=$recvTs latencyFromCmdMs=${if (cmdTimestamp > 0) recvTs - cmdTimestamp else -1}")
                android.util.Log.i("HomeSyncScreenTime", "SCREEN_TIME_COMMAND_RECEIVED childCode=$cleanCode targetChildId=$targetChildId commandType=$commandType commandId=$cmdId date=$date remoteLock=$remoteLock allowance=$remoteAllowance used=$used remaining=$rem curfewOverride=$curfewOverride timestamp=$recvTs")
                android.util.Log.i("HomeSyncScreenTime", "SCREEN_TIME_REMOTE_UPDATE childCode=$cleanCode used=$used remaining=$rem allowance=$remoteAllowance locked=$remoteLock")

                if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                    if (!isDisposed) {
                        onUpdate(rem, remoteLock, remoteAllowance, used, cmdId, cmdTimestamp, curfewOverride, targetChildId, commandType, date)
                    }
                } else {
                    mainHandler.post {
                        if (!isDisposed) {
                            onUpdate(rem, remoteLock, remoteAllowance, used, cmdId, cmdTimestamp, curfewOverride, targetChildId, commandType, date)
                        }
                    }
                }
            }

        return {
            isDisposed = true
            registration.remove()
        }
    }"""

pattern = r'    fun listenScreenTimeWithCommandDetails\(.*?\): \(\(\) -> Unit\)\? \{.*?(?=\n    fun listenScreenTimeWithCommand\()'
new_text = re.sub(pattern, replacement, text, flags=re.DOTALL)
if new_text != text:
    with open(path, 'w', encoding='utf-8') as f:
        f.write(new_text)
    print("Success")
else:
    print("No change")

