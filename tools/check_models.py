#!/usr/bin/env python3
"""Проверяет скачанные модели: открываются ли в onnxruntime и совпадают ли формы входов/выходов с ожидаемыми приложением."""
import sys, numpy as np, onnxruntime as ort
d = sys.argv[1]
def sess(n): return ort.InferenceSession(f"{d}/{n}.onnx", providers=["CPUExecutionProvider"])
x = (np.random.randn(16000) * 0.1).astype(np.float32)

s = sess("hubert"); print("hubert in", [(i.name, i.shape) for i in s.get_inputs()], "out", [(o.name, o.shape) for o in s.get_outputs()])
o = s.run(None, {s.get_inputs()[0].name: x.reshape(1, 1, -1)})[0]
print("hubert ->", o.shape); assert 768 in o.shape

for n in ("rmvpe", "fcpe"):
    s = sess(n); print(n, "in", [(i.name, i.shape) for i in s.get_inputs()], "out", [(o.name, o.shape) for o in s.get_outputs()])
    o = s.run(None, {s.get_inputs()[0].name: x.reshape(1, -1)})[0]
    print(n, "->", o.shape); assert o.shape[-1] == 360 and o.shape[1] == 16000 // 160 + 1
print("models ok")
