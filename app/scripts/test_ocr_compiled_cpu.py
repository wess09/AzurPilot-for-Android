#!/usr/bin/env python3
"""用 CompiledModel 验证 App 的私有文件 CPU 图、动态输出缓冲区和数值。

运行在 Linux 开发环境，使用同版本系列的 LiteRT CPU；不能代替 Android 驱动测试。

Checks private-file CPU views, dynamic output buffers, and values through CompiledModel.
Runs on Linux with the LiteRT CPU version family and cannot replace Android driver tests.
"""

import json
import tempfile
from pathlib import Path

import numpy as np
import onnxruntime as ort
from ai_edge_litert.compiled_model import CompiledModel
from ai_edge_litert.hardware_accelerator import HardwareAccelerator
from prepare_ocr_cpu_models import ASSETS, ROOT, SOURCES, specialize, softmax


def main():
    """真实推理并检查宽度和检测输出。 / Runs inference and checks widths and detector outputs."""
    manifest = json.loads((ASSETS / 'manifest.json').read_text())
    for spec in manifest['models']:
        conversion = spec['litert']
        original = ort.InferenceSession(str(SOURCES / spec['asset'].removeprefix('models/')),
                                        providers=['CPUExecutionProvider'])
        shapes = [(1, 3, 96, 160)] if spec['kind'] == 'detector' else [(1, 3, 48, w) for w in (317, 319, 320, 321, 324, 640)]
        for shape in shapes:
            data = specialize((ASSETS / conversion['asset']).read_bytes(), conversion['cpu_shape_patches'], shape)
            with tempfile.TemporaryDirectory(prefix='ocr-litert-cpu-') as directory:
                model_file = Path(directory) / f'{conversion["sha256"]}.tflite'
                model_file.write_bytes(data)
                compiled = CompiledModel.from_file(str(model_file), HardwareAccelerator.CPU)
                inputs = compiled.create_input_buffers(0)
                outputs = compiled.create_output_buffers(0)
                values = np.random.default_rng(20261009).uniform(-1, 1, shape).astype(np.float32)
                inputs[0].write(values.transpose(0, 2, 3, 1).copy() if conversion['input_layout'] == 'nhwc' else values)
                compiled.run_by_index(0, inputs, outputs)
                expected = original.run(None, {original.get_inputs()[0].name: values})[0]
                actual = outputs[0].read(expected.size, np.float32)
                if conversion['output_layout'] == 'nhwc':
                    actual = actual.reshape((1, expected.shape[2], expected.shape[3], expected.shape[1])).transpose(0, 3, 1, 2)
                else:
                    actual = actual.reshape(expected.shape)
                if conversion.get('output_postprocess') == 'softmax':
                    actual = softmax(actual)
                np.testing.assert_allclose(actual, expected, rtol=1e-3, atol=1e-4)
                if spec['kind'] == 'recognition':
                    np.testing.assert_array_equal(actual.argmax(-1), expected.argmax(-1))
                print(f'COMPILED_CPU_OK {Path(spec["asset"]).name} {shape}: {list(actual.shape)}', flush=True)
                del inputs, outputs, compiled


if __name__ == '__main__':
    main()
