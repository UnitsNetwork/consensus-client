import requests
from pywaves import pw


class Node(object):
    def __init__(
        self,
        pw=pw,
    ):
        self.pw = pw

    def connected_peers(self):
        url = f"{self.pw.NODE}/peers/connected"
        response = requests.get(url)
        if response.status_code == 200:
            return response.json()["peers"]
        else:
            raise Exception(f"Error: {response.status_code}, {response.text}")
