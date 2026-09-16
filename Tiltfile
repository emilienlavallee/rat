
def random_suffix():
  return str(local("openssl rand -hex 4")).strip()

config.define_string("mode", usage = "dev, app, or appdev")
config.define_string("debug", usage = "true or false")
config.define_string("java_home", usage = "path to JAVA_HOME (default: /usr/lib/jvm/java-21-openjdk)")
config.define_string("android_home", usage = "path to ANDROID_HOME (default: ~/Android/Sdk)")

cfg = config.parse()
mode = cfg.get("mode", "dev")
java_home = cfg.get("java_home", "")
android_home = cfg.get("android_home", "")
debug = cfg.get("debug", "false")

# 
# BLUETOOTH ENV VARS
# 
ble_service_uuid = "d6de50b6-456a-4e49-a172-a109443a83dc"
ble_receive_cmd_characteristic_uuid = "1696cc02-2f9d-4514-b673-5bd31938622f"
ble_send_notif_characteristic_uuid = "37f0faa0-b94e-4309-84ba-980d2a9bebc6"    
ble_cmd_sock_path = "ipc:///tmp/ble.cmd.%s" % random_suffix()
ble_notif_sock_path = "ipc:///tmp/ble.notif.%s" % random_suffix()

#
# TARGET ENV VARS
#
target_cmd_sock_path = "ipc:///tmp/target.cmd"

if mode == "app" or mode == "appdev":
  # No codegen step for the app: with the envelope gone the message union is a flat
  # anyOf, which quicktype collapses into one all-nullable class. The types are
  # hand-written in app/.../ble/AppMessage.kt instead.
  local_resource(
    "build-and-push-app",
    dir="app",
    cmd="export JAVA_HOME=%s ANDROID_HOME=%s && ./gradlew :app:assembleDebug && $ANDROID_HOME/platform-tools/adb uninstall sh.emilien.RATapp; $ANDROID_HOME/platform-tools/adb install app/build/outputs/apk/debug/app-debug.apk && $ANDROID_HOME/platform-tools/adb shell am start -n sh.emilien.RATapp/.MainActivity" % (java_home, android_home),
  )

  local_resource(
    "app-logcat",
    serve_cmd="""
      adb logcat -c
      while true; do
        pid=$(adb shell pidof -s sh.emilien.RATapp | tr -d '\\r')
        if [ -n "$pid" ]; then
          echo "--- attaching logcat to sh.emilien.RATapp (pid $pid) ---"
          adb logcat --pid="$pid"
        fi
        sleep 1
      done
    """,
    resource_deps=["build-and-push-app"],
  )

if mode == "dev" or mode == "appdev":

  #
  # TYPE GENERATION AND BUILDING
  # 
  
  local_resource(
    "controller-deps",
    dir="controller",
    cmd="pnpm install --frozen-lockfile"
  )

  local_resource(
    "controller-gen-types",
    dir="controller",
    cmd="pnpm run gen:types",
    resource_deps=["controller-deps"],
    deps=["schemas/app-message.schema.json", "schemas/target-message.schema.json"],
  )

  local_resource(
    "bluetooth-deps",
    dir="bluetooth",
    cmd="uv sync --locked"
  )

  local_resource(
    "bluetooth-gen-models",
    dir="bluetooth",
    cmd="uv run datamodel-codegen --input ../schemas/app-message.schema.json --input-file-type jsonschema --output-model-type pydantic_v2.BaseModel --preset standard-py312-20260619 --output src/bluetooth/messages.py",
    resource_deps=["bluetooth-deps"],
    deps=["schemas/app-message.schema.json"],
  )

  local_resource(
    "target-deps",
    dir="target",
    cmd="uv sync --locked"
  )

  local_resource(
    "target-gen-models",
    dir="target",
    cmd="uv run datamodel-codegen --input ../schemas/target-message.schema.json --input-file-type jsonschema --output-model-type pydantic_v2.BaseModel --preset standard-py312-20260619 --output src/target/messages.py",
    resource_deps=["target-deps"],
    deps=["schemas/target-message.schema.json"],
  )

  #
  # RUN SERVICES
  #

  local_resource(
    "run-controller",
    serve_dir="controller",
    serve_cmd="pnpm run dev",
    resource_deps=["controller-deps", "controller-gen-types"],
    serve_env={
      "DEBUG_LOGGING": debug,
      "BLE_CMD_OUT_SOCKET_PATH": ble_cmd_sock_path,
      "BLE_NOTIF_IN_SOCKET_PATH": ble_notif_sock_path,
      "TARGET_CMD_SOCKET_PATH": target_cmd_sock_path,
    },
  )


  local_resource(
    "run-bluetooth",
    serve_dir="bluetooth",
    serve_cmd="uv run bluetooth",
    resource_deps=["bluetooth-deps"],
    serve_env={
      "SERVICE_UUID": ble_service_uuid,
      "RECEIVE_CMD_CHARACTERISTIC_UUID": ble_receive_cmd_characteristic_uuid,
      "SEND_NOTIF_CHARACTERISTIC_UUID": ble_send_notif_characteristic_uuid,
      "BLE_CMD_SOCK_PATH": ble_cmd_sock_path,
      "BLE_NOTIF_SOCK_PATH": ble_notif_sock_path
    }
  )

  local_resource(
    "run-target",
    serve_dir="target",
    serve_cmd="uv run target",
    resource_deps=["target-deps", "target-gen-models"],
    serve_env={
      "TARGET_CMD_SOCK_PATH": target_cmd_sock_path
    }
  )
