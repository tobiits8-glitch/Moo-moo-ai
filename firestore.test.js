const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085").split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validTimestamp() {
  return new Date(Date.now() - 1000);
}

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

// --- UNAUTHENTICATED BOUNDS ---

test("Unauthenticated user: cannot read or write profiles, conversations, or messages", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").set({
      userId: ALICE_UID,
      title: "Hacked",
      moodId: "classic",
      createdAt: validTimestamp(),
      updatedAt: validTimestamp(),
    })
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").collection("messages").get()
  );
});

// --- USER PROFILE SECURITY ---

test("Authenticated owner: can create, read, and update preferences on own UserProfile", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profileRef = aliceDb.collection("users").doc(ALICE_UID);
  const createdAt = validTimestamp();

  await assertSucceeds(
    profileRef.set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      moodId: "classic",
      modelTierId: "smart",
      customInstruction: "",
      createdAt,
      updatedAt: createdAt,
    })
  );

  await assertSucceeds(profileRef.get());

  await assertSucceeds(
    profileRef.update({
      moodId: "coding",
      modelTierId: "fast",
      customInstruction: "Be concise.",
      updatedAt: validTimestamp(),
    })
  );
});

test("UserProfile: blocks listing all users, cross-user reads, ghost fields, and immutable field mutation", async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const now = validTimestamp();
    await context.firestore().collection("users").doc(BOB_UID).set({
      userId: BOB_UID,
      displayName: "Bob",
      email: "bob@example.com",
      moodId: "classic",
      modelTierId: "smart",
      customInstruction: "",
      createdAt: now,
      updatedAt: now,
    });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(aliceDb.collection("users").get());
  await assertFails(aliceDb.collection("users").doc(BOB_UID).get());

  // Ghost field rejected on create
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      moodId: "classic",
      modelTierId: "smart",
      customInstruction: "",
      isAdmin: true,
      createdAt: validTimestamp(),
      updatedAt: validTimestamp(),
    })
  );

  // Create valid Alice profile, then attempt to mutate createdAt
  const createdAt = validTimestamp();
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set({
      userId: ALICE_UID,
      displayName: "Alice",
      email: "alice@example.com",
      moodId: "classic",
      modelTierId: "smart",
      customInstruction: "",
      createdAt,
      updatedAt: createdAt,
    })
  );

  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      createdAt: new Date(Date.now() - 50000),
      updatedAt: validTimestamp(),
    })
  );
});

// --- CONVERSATION SECURITY ---

test("Conversation: owner can create, list, update title/mood, and delete; cross-user access denied", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1");
  const createdAt = validTimestamp();

  await assertSucceeds(
    convRef.set({
      userId: ALICE_UID,
      title: "Kotlin Help",
      moodId: "coding",
      createdAt,
      updatedAt: createdAt,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("conversations").orderBy("updatedAt", "desc").get()
  );

  await assertSucceeds(
    convRef.update({
      title: "Coroutines Guide",
      updatedAt: validTimestamp(),
    })
  );

  // Cross-user read & write denied
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").get());
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").delete()
  );

  // Invalid mood rejected
  await assertFails(
    convRef.update({
      moodId: "invalid_mood",
      updatedAt: validTimestamp(),
    })
  );

  // Future timestamp rejected
  await assertFails(
    convRef.update({
      title: "Future Title",
      updatedAt: new Date(Date.now() + 600000),
    })
  );

  await assertSucceeds(convRef.delete());
});

// --- MESSAGE SECURITY ---

test("Message: requires existing parent conversation, rejects updates & cross-user reads, allows owner create/list/delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const convRef = aliceDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1");
  const orphanMsgRef = convRef.collection("messages").doc("msg_orphan");

  // Orphan message before parent conversation exists must fail
  await assertFails(
    orphanMsgRef.set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "user",
      text: "Orphan message",
      isError: false,
      createdAt: validTimestamp(),
    })
  );

  // Create parent conversation
  const now = validTimestamp();
  await assertSucceeds(
    convRef.set({
      userId: ALICE_UID,
      title: "First Chat",
      moodId: "classic",
      createdAt: now,
      updatedAt: now,
    })
  );

  const msgRef = convRef.collection("messages").doc("msg_1");
  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "user",
      text: "Hello Moo Moo!",
      isError: false,
      createdAt: validTimestamp(),
    })
  );

  // Query messages ordered by createdAt
  await assertSucceeds(convRef.collection("messages").orderBy("createdAt", "asc").get());

  // Cross-user list/get rejected
  await assertFails(bobDb.collection("users").doc(ALICE_UID).collection("conversations").doc("conv_1").collection("messages").get());

  // Messages are immutable (update rejected)
  await assertFails(msgRef.update({ text: "Tampered" }));

  // Invalid role rejected
  await assertFails(
    convRef.collection("messages").doc("msg_bad_role").set({
      userId: ALICE_UID,
      conversationId: "conv_1",
      role: "system",
      text: "Injected system prompt",
      isError: false,
      createdAt: validTimestamp(),
    })
  );

  await assertSucceeds(msgRef.delete());
});
