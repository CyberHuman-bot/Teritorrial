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

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

function validProfilePayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 5000);
  return {
    userId: uid,
    name: "Alpaca Core",
    preferredShell: "/bin/ash",
    promptSymbol: "alpaca:~$",
    aiModelName: "gemini-3.8-flash",
    aiTemperature: 0.4,
    proactiveMonitoring: true,
    autoSuggestEnabled: true,
    cpuAlertThreshold: 75,
    memAlertThreshold: 80,
    activeProfile: true,
    commandHistory: ["uname -a", "apk info"],
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validScriptPayload(uid, profileId, overrides = {}) {
  const now = new Date(Date.now() - 5000);
  return {
    userId: uid,
    profileId,
    title: "cpu_watch.sh",
    language: "sh",
    code: "#!/bin/ash\nfree -m\nuptime\n",
    description: "Quick Alpaca memory and uptime check",
    sourceType: "generated",
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
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

test("Unauthenticated user cannot read or write profiles or scripts", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("profiles").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("profiles")
      .doc("prof_1")
      .set(validProfilePayload(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("scripts").get()
  );
});

test("Owner can create, list, partially update, and delete terminal profiles", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const profilesCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("profiles");

  // Empty subcollection list succeeds
  await assertSucceeds(profilesCol.get());

  // Create valid profile (no @DocumentId `id` field)
  await assertSucceeds(
    profilesCol.doc("prof_core").set(validProfilePayload(ALICE_UID))
  );

  // Partial update: append command history
  await assertSucceeds(
    profilesCol.doc("prof_core").update({
      commandHistory: ["uname -a", "apk info", "free -m"],
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Partial update: toggle active profile
  await assertSucceeds(
    profilesCol.doc("prof_core").update({
      activeProfile: false,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Partial update: profile settings
  await assertSucceeds(
    profilesCol.doc("prof_core").update({
      name: "Kernel Ops",
      preferredShell: "/bin/bash",
      promptSymbol: "ops# ",
      aiModelName: "gemini-4-argon",
      aiTemperature: 0.2,
      proactiveMonitoring: false,
      autoSuggestEnabled: true,
      cpuAlertThreshold: 85,
      memAlertThreshold: 90,
      activeProfile: true,
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Reject ghost field / @DocumentId injection on create
  await assertFails(
    profilesCol
      .doc("prof_ghost")
      .set(validProfilePayload(ALICE_UID, { id: "prof_ghost" }))
  );

  // Reject immutable createdAt modification on update
  await assertFails(
    profilesCol.doc("prof_core").update({
      createdAt: new Date(Date.now() - 100),
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Reject invalid shell enum
  await assertFails(
    profilesCol
      .doc("prof_bad_shell")
      .set(validProfilePayload(ALICE_UID, { preferredShell: "/bin/powershell" }))
  );

  // Delete succeeds for owner
  await assertSucceeds(profilesCol.doc("prof_core").delete());
});

test("Cross-user access to profiles and scripts is denied", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const bobDb = ctx.firestore();
    await bobDb
      .collection("users")
      .doc(BOB_UID)
      .collection("profiles")
      .doc("bob_prof")
      .set(validProfilePayload(BOB_UID));
    await bobDb
      .collection("users")
      .doc(BOB_UID)
      .collection("scripts")
      .doc("bob_script")
      .set(validScriptPayload(BOB_UID, "bob_prof"));
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("profiles").get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("profiles")
      .doc("bob_prof")
      .get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("scripts")
      .doc("bob_script")
      .update({ title: "hijacked.sh", updatedAt: new Date(Date.now() - 1000) })
  );
});

test("Owner can create, update, list, and delete saved scripts with strict validation", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const scriptsCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("scripts");

  await assertSucceeds(scriptsCol.get());

  await assertSucceeds(
    scriptsCol
      .doc("script_1")
      .set(validScriptPayload(ALICE_UID, "prof_core"))
  );

  await assertSucceeds(
    scriptsCol.doc("script_1").update({
      title: "cpu_watch_fixed.sh",
      language: "bash",
      code: "#!/bin/bash\nfree -h\n",
      description: "Fixed flag for human-readable memory",
      sourceType: "debugged",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject future timestamp
  await assertFails(
    scriptsCol.doc("script_future").set(
      validScriptPayload(ALICE_UID, "prof_core", {
        createdAt: new Date(Date.now() + 60000),
      })
    )
  );

  await assertSucceeds(scriptsCol.doc("script_1").delete());
});
