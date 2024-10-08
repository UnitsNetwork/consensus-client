import pywaves as pw
import requests


class ExtendedOracle(pw.Oracle):
    def evaluate(self, query):
        url = f"{self.pw.NODE}/utils/script/evaluate/{self.oracleAddress}"
        headers = {"Content-Type": "application/json"}
        data = {"expr": query}

        response = requests.post(url, headers=headers, json=data)

        if response.status_code == 200:
            return response.json()
        else:
            raise Exception(f"Error: {response.status_code}, {response.text}")
