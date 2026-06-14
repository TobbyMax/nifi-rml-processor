import yaml
import yatter


def transpile(yaml_str: str) -> str:
    """Transpile YARRRML (YAML string) to RML/Turtle using yatter."""
    yarrrml_dict = yaml.safe_load(yaml_str)
    result = yatter.translate(yarrrml_dict)
    if result is None:
        raise ValueError("yatter failed to transpile YARRRML mapping")
    return result
