import importlib.util
import os


def run_main_from_module(filepath):
    module_name = os.path.splitext(os.path.basename(filepath))[0]

    spec = importlib.util.spec_from_file_location(module_name, filepath)
    assert spec is not None

    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # type: ignore

    if hasattr(module, "main"):
        module.main()
    else:
        raise Exception(f"No main function in {module}")


tests_dir = "tests"
for filename in os.listdir(tests_dir):
    if filename.endswith(".py"):
        filepath = os.path.join(tests_dir, filename)
        run_main_from_module(filepath)
