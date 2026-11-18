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

# Модели разделения (MDX-Net, UVR): вход [1,4,3072,256]. Необязательные: битый файл удаляем с предупреждением,
# приложение покажет «не установлена».
import os
for n in ("mdx_voc_ft", "mdx_kim_vocal2"):
    path = f"{d}/{n}.onnx"
    if not os.path.exists(path):
        print(f"::warning::{n}: файла нет, разделение вокала этой моделью будет недоступно"); continue
    try:
        s = sess(n); inp = s.get_inputs()[0]
        print(n, "in", inp.name, inp.shape, "out", [(o.name, o.shape) for o in s.get_outputs()])
        assert list(inp.shape[1:]) == [4, 3072, 256], f"неожиданная форма входа {inp.shape}"
        z = np.zeros((1, 4, 3072, 256), np.float32); z[0, 0, 100:200, :] = 0.5
        o = s.run(None, {inp.name: z})[0]
        assert o.shape == (1, 4, 3072, 256) and np.isfinite(o).all(), f"неожиданный выход {o.shape}"
        print(n, "ok")
    except Exception as e:
        print(f"::warning::{n}: модель не прошла проверку ({e}); удаляю"); os.remove(path)
