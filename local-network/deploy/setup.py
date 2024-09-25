from setuptools import setup

setup(
    name="local",
    version="0.1",
    packages=["local"],
    include_package_data=True,
    install_requires=[
        "solc-select",
        "units_network @ git+https://github.com/UnitsNetwork/examples.git@python",
    ],
    description="Scripts and tests for a locally deployed Unit network",
    url="https://github.com/UnitsNetwork/consensus-client",
)
