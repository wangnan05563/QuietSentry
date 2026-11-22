import os, sys, time, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ui_walk as u

u.OUT = r"E:\qs-ui-logs"
CAM = "emulator-5556"
VIEW = "emulator-5554"

def show(tag, nodes):
    print("----", tag, "activity=", u.activity())
    for n in nodes:
        if n["text"].strip() or n["desc"].strip() or n["clickable"] or "EditText" in n["cls"]:
            print(n["rid"].split("/")[-1], n["cls"].split(".")[-1], repr(n["text"]), repr(n["desc"]),
                  "click=", n["clickable"], "enabled=", n["enabled"], "bounds=", n["x1"], n["y1"], n["x2"], n["y2"])

u.SER = CAM
u.adb("forward", "tcp:18081", "tcp:8080")
nodes, _ = u.snapshot("r8p3-cam-before", do_shot=True, do_audit=False)
u.tap_rid(nodes, "pairing_refresh"); time.sleep(1)
nodes, _ = u.snapshot("r8p3-cam-code", do_shot=True, do_audit=False)
code = None
for n in nodes:
    m = re.search(r"配对码 (\d{6})", n["text"])
    if m: code = m.group(1)
print("CODE", code)
show("cam-code", nodes)

u.SER = VIEW
nodes, _ = u.snapshot("r8p3-list", do_shot=True, do_audit=False); show("list", nodes)
u.tap_rid(nodes, "device_list_add"); time.sleep(1)
nodes, _ = u.snapshot("r8p3-options", do_shot=True, do_audit=False); show("options", nodes)
u.tap_text(nodes, "手动添加"); time.sleep(1)
nodes, _ = u.snapshot("r8p3-dialog", do_shot=True, do_audit=False); show("dialog", nodes)

edits = [n for n in nodes if "EditText" in n["cls"]]
print("EDIT_FIELDS", len(edits))
if len(edits) >= 3 and code:
    e0 = edits[0]
    u.adb("shell", "input", "tap", str(e0["cx"]), str(e0["cy"])); time.sleep(0.8)
    u.adb("shell", "input", "text", "10.0.2.2"); time.sleep(0.8)
    u.adb("shell", "input", "keyevent", "61"); time.sleep(0.4)
    u.adb("shell", "input", "keyevent", "61"); time.sleep(0.4)
    u.adb("shell", "input", "text", code); time.sleep(0.8)
    u.adb("shell", "input", "keyevent", "4"); time.sleep(0.8)
    nodes, _ = u.snapshot("r8p3-filled", do_shot=True, do_audit=False); show("filled", nodes)
    u.tap_text(nodes, "配对", exact=True); time.sleep(4)
    nodes, _ = u.snapshot("r8p3-paired", do_shot=True, do_audit=False); show("paired", nodes)
