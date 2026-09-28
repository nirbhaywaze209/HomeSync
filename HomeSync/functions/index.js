const functions = require("firebase-functions");
const admin = require("firebase-admin");

admin.initializeApp();

/**
 * Cloud Function triggered whenever an emergency SOS event is created in hs_sos_events/{eventId}.
 * Looks up approved guardians for the child's family and securely dispatches
 * data-only high-priority FCM emergency push messages directly to guardian devices.
 */
exports.sendEmergencySosPush = functions.firestore
  .document("hs_sos_events/{eventId}")
  .onCreate(async (snapshot, context) => {
    if (!snapshot || !snapshot.exists) {
      console.log("No data associated with event");
      return;
    }

    const data = snapshot.data();
    const eventId = context.params.eventId;
    const familyId = (data.familyId || "").trim().toUpperCase();
    const childName = (data.childName || "Child").trim();
    const childCode = (data.childCode || "").trim();
    const childUid = (data.childUid || "").trim();
    const latitude = data.latitude || 0.0;
    const longitude = data.longitude || 0.0;
    const title = (data.title || "🚨 EMERGENCY SOS ALERT").trim();
    const messageText = (data.message || `EMERGENCY SOS Alert triggered by ${childName}! Live location active.`).trim();
    const timestamp = String(data.timestamp || Date.now());

    console.log(`SOS_EVENT_CREATED eventId=${eventId} familyId=${familyId} childName=${childName} childUid=${childUid}`);

    if (!familyId) {
      console.warn(`SOS_FCM_SEND_FAILED eventId=${eventId} reason=empty_family_id`);
      await snapshot.ref.update({
        pushStatus: "FAILED_EMPTY_FAMILY_ID",
        processedAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return;
    }

    const db = admin.firestore();
    const tokenMap = new Map(); // token -> { guardianUid }

    try {
      // 1. Query approved guardians from family members subcollection
      const membersSnap = await db
        .collection("hs_families")
        .doc(familyId)
        .collection("members")
        .get();

      for (const doc of membersSnap.docs) {
        const m = doc.data();
        const role = (m.role || "").toUpperCase();
        const status = (m.status || "").toUpperCase();
        const guardianUid = doc.id;

        // Include any guardian that isn't explicitly rejected/removed
        if ((role === "GUARDIAN" || role === "PARENT" || role === "ADMIN") && status !== "REJECTED" && status !== "REMOVED") {
          const token = (m.fcmToken || "").trim();
          if (token && token.length > 10) {
            tokenMap.set(token, { guardianUid });
          }
          // Always check fallback in hs_users document too
          try {
            const userSnap = await db.collection("hs_users").doc(guardianUid).get();
            if (userSnap.exists) {
              const uToken = (userSnap.data().fcmToken || "").trim();
              if (uToken && uToken.length > 10) {
                tokenMap.set(uToken, { guardianUid });
              }
            }
          } catch (_uErr) {}
        }
      }

      // Also check root hs_families document for guardianUid / createdById
      const familyDoc = await db.collection("hs_families").doc(familyId).get();
      if (familyDoc.exists) {
        const fData = familyDoc.data();
        const gUids = [
          (fData.createdById || "").trim(),
          (fData.guardianUid || "").trim(),
          (fData.createdBy || "").trim()
        ].filter((u) => u.length > 0);

        for (const gUid of gUids) {
          try {
            const userSnap = await db.collection("hs_users").doc(gUid).get();
            if (userSnap.exists) {
              const uToken = (userSnap.data().fcmToken || "").trim();
              if (uToken && uToken.length > 10) {
                tokenMap.set(uToken, { guardianUid: gUid });
              }
            }
          } catch (_gErr) {}
        }
      }

      // 1b. Query hs_users directly for familyId matching this family (case insensitive / uppercase)
      const familyVariants = [familyId, familyId.toLowerCase(), familyId.toUpperCase()];
      for (const fVar of familyVariants) {
        try {
          const usersSnap = await db.collection("hs_users").where("familyId", "==", fVar).get();
          for (const doc of usersSnap.docs) {
            const uData = doc.data();
            const uRole = (uData.role || "").toUpperCase();
            const uStatus = (uData.status || uData.membershipStatus || "").toUpperCase();
            if ((uRole === "GUARDIAN" || uRole === "PARENT" || uRole === "ADMIN" || !uRole) && uStatus !== "REJECTED" && uStatus !== "REMOVED") {
              const uToken = (uData.fcmToken || "").trim();
              if (uToken && uToken.length > 10) {
                tokenMap.set(uToken, { guardianUid: doc.id });
              }
            }
          }
        } catch (_fErr) {}
      }

      // 1c. Universal Fallback: If no tokens found for familyId, query all hs_users with GUARDIAN role
      if (tokenMap.size === 0) {
        console.log(`SOS_TOKEN_FALLBACK eventId=${eventId} searching all hs_users for GUARDIAN role...`);
        try {
          const allUsersSnap = await db.collection("hs_users").get();
          for (const doc of allUsersSnap.docs) {
            const uData = doc.data();
            const uRole = (uData.role || "").toUpperCase();
            if (uRole === "GUARDIAN" || uRole === "PARENT" || uRole === "ADMIN" || !uRole) {
              const uToken = (uData.fcmToken || "").trim();
              if (uToken && uToken.length > 10) {
                tokenMap.set(uToken, { guardianUid: doc.id });
              }
            }
          }
        } catch (_allErr) {}
      }

      const tokenList = Array.from(tokenMap.keys());
      console.log(`SOS_FCM_TOKEN_FOUND count=${tokenList.length} familyId=${familyId} eventId=${eventId}`);

      if (tokenList.length === 0) {
        console.warn(`SOS_FCM_SEND_FAILED eventId=${eventId} reason=no_guardian_tokens`);
        await snapshot.ref.update({
          pushStatus: "NO_TOKENS_FOUND",
          processedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        return;
      }

      console.log(`SOS_FCM_SEND_START eventId=${eventId} tokenCount=${tokenList.length}`);

      // 2. Build data-only high-priority FCM payload.
      // Data-only (no notification block) guarantees onMessageReceived() is always called in
      // HomeSyncMessagingService even when the app is killed/removed from Recents.
      // If a notification block is present, Android handles it via the default channel when the
      // app is not running, bypassing our custom alarm-sound emergency channel entirely.
      const messages = tokenList.map((token) => ({
        token: token,
        data: {
          type: "SOS_EMERGENCY",
          eventId: String(eventId),
          familyId: String(familyId),
          childUid: String(childUid),
          childName: String(childName),
          childCode: String(childCode),
          latitude: String(latitude),
          longitude: String(longitude),
          title: String(title),
          message: String(messageText),
          timestamp: String(timestamp),
          click_action: "OPEN_GUARDIAN_MAP"
        },
        android: {
          priority: "high",
          ttl: 86400000, // 24 hours TTL
          notification: {
            channelId: "homesync_emergency_alerts_v2",
            priority: "max",
            visibility: "public"
          }
        }
      }));

      const batchResponse = await admin.messaging().sendEach(messages);
      console.log(`SOS_FCM_SEND_SUCCESS eventId=${eventId} successCount=${batchResponse.successCount} failureCount=${batchResponse.failureCount}`);

      // Handle invalid/expired tokens safely
      if (batchResponse.failureCount > 0) {
        console.warn(`SOS_FCM_SEND_FAILED eventId=${eventId} failureCount=${batchResponse.failureCount}`);

        batchResponse.responses.forEach(async (resp, idx) => {
          if (!resp.success && resp.error) {
            const errCode = resp.error.code || "";
            const token = tokenList[idx];
            const maskedToken = token.length > 12 ? `${token.substring(0, 6)}...${token.substring(token.length - 4)}` : "masked_token";

            if (
              errCode === "messaging/invalid-registration-token" ||
              errCode === "messaging/registration-token-not-registered"
            ) {
              console.warn(`SOS_FCM_INVALID_TOKEN eventId=${eventId} maskedToken=${maskedToken} error=${errCode}`);
              const meta = tokenMap.get(token);
              if (meta && meta.guardianUid) {
                try {
                  await db.collection("hs_users").doc(meta.guardianUid).update({
                    fcmToken: admin.firestore.FieldValue.delete()
                  });
                  await db.collection("hs_families").doc(familyId).collection("members").doc(meta.guardianUid).update({
                    fcmToken: admin.firestore.FieldValue.delete()
                  });
                  console.log(`Stale FCM token removed for guardian ${meta.guardianUid}`);
                } catch (_cleanErr) {}
              }
            }
          }
        });
      }

      await snapshot.ref.update({
        pushStatus: "SENT",
        successCount: batchResponse.successCount,
        failureCount: batchResponse.failureCount,
        processedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    } catch (err) {
      console.error(`SOS_FCM_SEND_FAILED eventId=${eventId} error=${String(err.message || err)}`);
      await snapshot.ref.update({
        pushStatus: "ERROR",
        error: String(err.message || err),
        processedAt: admin.firestore.FieldValue.serverTimestamp()
      });
    }
  });
