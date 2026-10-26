"""Registers the TV launcher as a client on the Bosch Smart Home Controller.

Enter the system password, then hold the button on the controller until the
LEDs flash. The script waits for pairing mode and registers automatically.
"""
import base64, getpass, json, os, socket, ssl, subprocess, sys, time, urllib.request

if len(sys.argv) < 2:
    sys.exit("Aufruf: python register-bosch.py <IP des Bosch Smart Home Controllers>")
HOST = sys.argv[1]
here = os.path.dirname(os.path.abspath(__file__))
assets = os.path.join(here, "..", "assets")
cert, key = os.path.join(here, "client-cert.pem"), os.path.join(here, "client-key.pem")
p12 = os.path.join(assets, "bosch-client.p12")

# Client certificate for the launcher (kept out of git, see .gitignore)
if not os.path.exists(cert):
    subprocess.run(["openssl", "req", "-x509", "-nodes", "-days", "3650", "-newkey", "rsa:2048",
                    "-keyout", key, "-out", cert, "-subj", "/CN=oss_tvhome"], check=True)
    # -legacy: Android cannot read OpenSSL 3's default PKCS#12 encryption
    subprocess.run(["openssl", "pkcs12", "-export", "-legacy", "-in", cert, "-inkey", key,
                    "-out", p12, "-passout", "pass:tvhome", "-name", "tvhome"], check=True)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE  # controller uses a self-signed certificate
ctx.load_cert_chain(cert, key)

pw = getpass.getpass("Bosch Smart Home Systempasswort: ")
body = json.dumps({"@type": "client", "id": "oss_tvhome", "name": "OSS TV Home",
                   "primaryRole": "ROLE_RESTRICTED_CLIENT",
                   "certificate": open(cert).read()}).encode()
headers = {"Content-Type": "application/json", "api-version": "3.2",
           "Systempassword": base64.b64encode(pw.encode()).decode()}

print("Jetzt die Taste am Controller KURZ drücken (Controller II) - ich warte bis zu 10 Minuten ...")
for _ in range(300):
    try:
        req = urllib.request.Request(f"https://{HOST}:8443/smarthome/clients", body, headers, method="POST")
        with urllib.request.urlopen(req, context=ctx, timeout=10) as r:
            print("HTTP", r.status, "- erfolgreich registriert")
            break
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, e.read().decode(errors="replace")[:300])
        if e.code in (401, 403):
            print("Passwort falsch oder Pairing-Modus nicht aktiv.")
        break
    except (ssl.SSLError, socket.timeout, ConnectionError, urllib.error.URLError):
        time.sleep(2)
else:
    print("Kein Pairing-Modus erkannt (10 Minuten gewartet).")
