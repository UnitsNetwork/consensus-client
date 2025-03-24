import requests
from pywaves import pw


class ExtendedAddress(pw.Address):
    def scriptInfo(self):
        url = f"{self.pywaves.NODE}/addresses/scriptInfo/{self.address}"
        response = requests.get(url)

        if response.status_code == 200:
            return response.json()
        else:
            raise Exception(f"Error: {response.status_code}, {response.text}")
