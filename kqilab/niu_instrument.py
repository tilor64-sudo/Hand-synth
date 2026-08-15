from pathlib import Path
import re
import sys

if len(sys.argv) != 2:
    raise SystemExit("usage: niu_instrument.py <apktool-decoded-dir>")

root = Path(sys.argv[1])
TAG = "KQiAES_CAPTURE"


def locate(rel):
    hits = list(root.glob(f"smali*/{rel}"))
    if len(hits) != 1:
        raise SystemExit(f"expected exactly one {rel}, found {len(hits)}: {hits}")
    return hits[0]


def bump_locals(block, extra):
    m = re.search(r"(?m)^(\s*)\.locals\s+(\d+)\s*$", block)
    if not m:
        raise SystemExit("method has no .locals directive")
    old = int(m.group(2))
    new = old + extra
    block = block[:m.start()] + f"{m.group(1)}.locals {new}" + block[m.end():]
    return block, old


def patch_setter(path, method_sig):
    s = path.read_text()
    pat = re.compile(rf"(?ms)^\.method[^\n]*\b{re.escape(method_sig)}\n.*?^\.end method$")
    m = pat.search(s)
    if not m:
        raise SystemExit(f"setter {method_sig} not found in {path}")
    block = m.group(0)
    if TAG in block:
        return
    block, first_free = bump_locals(block, 1)
    insert = (
        f'    const-string v{first_free}, "{TAG}"\n'
        f'    invoke-static {{v{first_free}, p1}}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I\n'
    )
    block = re.sub(r"(?m)^(\s*\.locals\s+\d+\s*)$", r"\1\n" + insert.rstrip(), block, count=1)
    s = s[:m.start()] + block + s[m.end():]
    path.write_text(s)
    print("patched setter", path, method_sig)


def patch_getter(path, method_sig):
    s = path.read_text()
    pat = re.compile(rf"(?ms)^\.method[^\n]*\b{re.escape(method_sig)}\n.*?^\.end method$")
    m = pat.search(s)
    if not m:
        raise SystemExit(f"getter {method_sig} not found in {path}")
    block = m.group(0)
    if TAG in block:
        return
    block, first_free = bump_locals(block, 1)
    # Getter is expected to load the aesSecret field into a local then return it.
    ret = re.search(r"(?m)^(\s*)return-object\s+(v\d+)\s*$", block)
    if not ret:
        raise SystemExit(f"return-object local not found in {method_sig}")
    indent, value_reg = ret.group(1), ret.group(2)
    log = (
        f'{indent}const-string v{first_free}, "{TAG}"\n'
        f'{indent}invoke-static {{v{first_free}, {value_reg}}}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I\n'
    )
    block = block[:ret.start()] + log + block[ret.start():]
    s = s[:m.start()] + block + s[m.end():]
    path.write_text(s)
    print("patched getter", path, method_sig)


ble = locate("com/niu/cloud/modules/carble/bean/BleConnectInfo.smali")
extdev = locate("com/niu/cloud/bean/ExtDeviceInfo.smali")

patch_setter(ble, "setAesSecret(Ljava/lang/String;)V")
patch_getter(ble, "getAesSecret()Ljava/lang/String;")
patch_getter(extdev, "getAesSecret()Ljava/lang/String;")

print("NIU AES instrumentation patch complete")
