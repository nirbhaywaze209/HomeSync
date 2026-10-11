import urllib.request, json
url = "https://firestore.googleapis.com/v1/projects/homesync-app-4cee2/databases/(default)/documents/hs_screentime/HS-K87BEU"
req = urllib.request.Request(url)
try:
    with urllib.request.urlopen(req) as response:
        print(response.read().decode())
except Exception as e:
    print(e)
