#!/usr/bin/env python3
"""Test engineer.yarrrml.yml mapping locally using morph-kgc."""

import os
import tempfile
from pathlib import Path
import morph_kgc

REPO = Path(__file__).resolve().parent.parent.parent
MAPPING = REPO / "demo/lada-example/mappings/engineer.yarrrml.yml"
DATA = REPO / "demo/lada-example/engineer.json"

def test_mapping():
    with tempfile.TemporaryDirectory() as tmpdir:
        tmpdir = Path(tmpdir)
        output_dir = tmpdir / "output"
        output_dir.mkdir()
        
        # Copy data file to tmpdir as input.json
        import shutil
        input_json = tmpdir / "input.json"
        shutil.copy(DATA, input_json)
        
        # Create config for morph-kgc
        config_content = f"""
[DataSource1]
mappings: {MAPPING}
file_path: {input_json}
"""
        config_file = tmpdir / "config.ini"
        config_file.write_text(config_content)
        
        print(f"Config: {config_file}")
        print(f"Mapping: {MAPPING}")
        print(f"Data: {input_json}")
        print()
        
        try:
            # Run morph-kgc
            graph = morph_kgc.materialize(config_file)
            
            # Print results
            print(f"=== Generated {len(graph)} triples ===")
            for s, p, o in graph:
                print(f"  {s} {p} {o}")
                
        except Exception as e:
            print(f"ERROR: {e}")
            import traceback
            traceback.print_exc()

if __name__ == "__main__":
    test_mapping()
