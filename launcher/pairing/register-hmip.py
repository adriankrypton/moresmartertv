"""Couples the TV launcher with a Homematic IP Access Point (cloud API).

Run it, enter the SGTIN printed on the back of the access point
(e.g. 3014-F711-A000-xxxx-xxxx-xxxx), then press the blue button on the
access point when asked. Writes ../assets/hmip.json (kept out of git).
"""
import hashlib, json, os, sys, time, urllib.request, uuid

CLIENT = {"apiVersion": "10", "applicationIdentifier": "tvhome", "applicationVersion": "1.0",
          "deviceManufacturer": "none", "deviceType": "Computer", "language": "de_DE",
          "osType": "Linux", "osVersion": "1.0"}

def post(url, body, headers):
    req = urllib.request.Request(url, json.dumps(body).encode(), headers, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        txt = r.read().decode() or "{}"
        return r.status, json.loads(txt)

sgtin = input("SGTIN des Access Points: ").replace("-", "").strip().upper()
auth = hashlib.sha512((sgtin + "jiLpVitHvWnIGD1yo7MA").encode()).hexdigest().upper()
h = {"content-type": "application/json", "accept": "application/json", "VERSION": "12",
     "CLIENTAUTH": auth, "ACCESSPOINT-ID": sgtin}
_, host = post("https://lookup.homematic.com:48335/getHost", {"clientCharacteristics": CLIENT, "id": sgtin}, h)
rest = host["urlREST"]
device = str(uuid.uuid4())
post(rest + "/hmip/auth/connectionRequest", {"deviceId": device, "deviceName": "TV Home", "sgtin": sgtin}, h)
print("Jetzt die blaue Taste am Access Point drücken ...")
for _ in range(60):
    try:
        if post(rest + "/hmip/auth/isRequestAcknowledged", {"deviceId": device}, h)[0] == 200:
            break
    except Exception:
        pass
    time.sleep(2)
else:
    sys.exit("Keine Bestätigung erhalten.")
_, tok = post(rest + "/hmip/auth/requestAuthToken", {"deviceId": device}, h)
post(rest + "/hmip/auth/confirmAuthToken", {"deviceId": device, "authToken": tok["authToken"]}, h)
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "hmip.json")
with open(out, "w") as f:
    json.dump({"sgtin": sgtin, "clientAuth": auth, "authToken": tok["authToken"], "urlREST": rest}, f)
print("Fertig, gespeichert in hmip.json")
