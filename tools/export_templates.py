#!/usr/bin/env python3
"""
Строит ONNX-шаблоны генератора RVC v2 (SynthesizerTrnMsNSFsidM) для 32k/40k/48k.

Шаблон = граф + внешний файл весов. Приложение пишет веса из .pth в weights.bin потоково
по смещениям из manifest (без protobuf и без загрузки модели целиком в RAM).

Перед экспортом снимаем weight_norm: в графе остаются обычные conv-веса "<имя>.weight".
Приложение само сворачивает weight_g/weight_v из .pth: w = v * g / ||v|| (норма по всем осям, кроме 0).

usage: export_templates.py <клон RVC> <папка вывода>
"""
import json, os, sys, tempfile
import numpy as np
import torch
import onnx
from onnx import external_data_helper as edh

RVC, OUT = sys.argv[1], sys.argv[2]

# Раскладка RVC менялась (infer_pack / lib/infer_pack / infer/lib/infer_pack), поэтому ищем models_onnx.py сами,
# копируем его папку в пакет rvcpkg и переписываем импорты вида "<что-угодно>.infer_pack" на rvcpkg.
import glob, re, shutil
found = sorted(glob.glob(os.path.join(RVC, "**", "models_onnx.py"), recursive=True))
if not found:
    sys.exit("models_onnx.py не найден в " + RVC + " — структура репозитория RVC изменилась")
src_dir = os.path.dirname(found[0])
print("models_onnx.py:", found[0])
pkg_root = tempfile.mkdtemp()
pkg = os.path.join(pkg_root, "rvcpkg")
shutil.copytree(src_dir, pkg, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
open(os.path.join(pkg, "__init__.py"), "a").close()
for f in glob.glob(os.path.join(pkg, "**", "*.py"), recursive=True):
    t = open(f, encoding="utf-8").read()
    t = re.sub(r"(?m)^(\s*(?:from|import)\s+)(?:[\w.]*\.)?infer_pack\b", r"\1rvcpkg", t)
    open(f, "w", encoding="utf-8").write(t)
sys.path.insert(0, pkg_root)
try:
    from rvcpkg.models_onnx import SynthesizerTrnMsNSFsidM  # noqa: E402
except ModuleNotFoundError as e:
    sys.exit(f"Для кода RVC не хватает Python-пакета «{e.name}»: добавьте его в pip install в build.yml")

# порядок = cpt["config"] в .pth RVC
def config(sr, rates, kernels):
    return [1025, 32, 192, 192, 768, 2, 6, 3, 0, "1", [3, 7, 11],
            [[1, 3, 5], [1, 3, 5], [1, 3, 5]], rates, 512, kernels,
            1,      # spk_embed_dim: один спикер, из .pth берём строку 0
            256, sr]

CONFIGS = {
    "v2_32k": (32000, [10, 8, 2, 2], [20, 16, 4, 4]),
    "v2_40k": (40000, [10, 10, 2, 2], [16, 16, 4, 4]),
    "v2_48k": (48000, [12, 10, 2, 2], [24, 20, 4, 4]),
}
ALIGN = 4096
os.makedirs(OUT, exist_ok=True)

for tid, (sr, rates, kernels) in CONFIGS.items():
    net = SynthesizerTrnMsNSFsidM(*config(sr, rates, kernels), is_half=False, version="v2").eval()
    for m in net.modules():
        try:
            torch.nn.utils.remove_weight_norm(m)
        except ValueError:
            pass
    # torch.onnx.export склеивает инициализаторы с одинаковыми значениями (нулевые bias/post, единичные gamma),
    # и такие веса пропадают из графа под своими именами. Делаем все веса уникальными случайными:
    # значения шаблона всё равно заменяются весами из .pth.
    torch.manual_seed(0)
    with torch.no_grad():
        for pname, prm in net.named_parameters():
            if pname.endswith("gamma"):
                prm.copy_(1 + 0.01 * torch.randn_like(prm))
            else:
                prm.copy_(0.05 * torch.randn_like(prm))
    n = 200
    args = (
        torch.rand(1, n, 768), torch.LongTensor([n]),
        torch.randint(5, 255, (1, n)), torch.rand(1, n),
        torch.LongTensor([0]), torch.rand(1, 192, n),
    )
    tmp = os.path.join(tempfile.mkdtemp(), "m.onnx")
    torch.onnx.export(
        net, args, tmp, opset_version=13, do_constant_folding=False,
        input_names=["phone", "phone_lengths", "pitch", "pitchf", "ds", "rnd"],
        output_names=["audio"],
        dynamic_axes={"phone": [1], "pitch": [1], "pitchf": [1], "rnd": [2]},
    )
    model = onnx.load(tmp)
    params = {k: v for k, v in net.state_dict().items() if not k.startswith("enc_q.")}
    inits = {t.name: t for t in model.graph.initializer}

    missing = [k for k in params if k not in inits]
    print(f"[{tid}] параметров {len(params)}, не вошло в граф: {len(missing)}")
    if missing:
        print("   не вошли:", missing[:30])
    # Параметр, которого нет в графе под своим именем, приложение не сможет подставить, и голос стал бы
    # шумом с тихой ошибкой. Поэтому падаем сразу, а не собираем сломанный шаблон.
    if missing:
        sys.exit(f"[{tid}] экспорт переименовал/потерял веса: {missing[:10]}")

    manifest, offset = {}, 0
    for name, t in inits.items():
        if name not in params:
            continue
        arr = params[name].detach().cpu().numpy().astype(np.float32)
        if tuple(t.dims) != tuple(arr.shape):
            sys.exit(f"[{tid}] форма {name}: граф {tuple(t.dims)} != state_dict {tuple(arr.shape)}")
        offset = (offset + ALIGN - 1) // ALIGN * ALIGN
        nbytes = arr.size * 4
        for f in ("raw_data", "float_data", "int32_data", "int64_data", "double_data"):
            t.ClearField(f)
        edh.set_external_data(t, location="weights.bin", offset=offset, length=nbytes)
        manifest[name] = {"offset": offset, "numel": int(arr.size)}
        offset += nbytes
    total = (offset + ALIGN - 1) // ALIGN * ALIGN

    onnx.save_model(model, os.path.join(OUT, f"{tid}.onnx"))
    with open(os.path.join(OUT, f"{tid}.json"), "w") as f:
        json.dump({"id": tid, "sr": sr, "hop": sr // 100, "upsample_rates": rates,
                   "total_bytes": total, "tensors": manifest}, f)

    # Смоук-тест: ровно те файлы, что получит телефон, через onnxruntime
    import onnxruntime as ort
    d = tempfile.mkdtemp()
    os.replace(os.path.join(OUT, f"{tid}.onnx"), os.path.join(d, "model.onnx"))
    with open(os.path.join(d, "weights.bin"), "wb") as f:
        f.truncate(total)
        for name, m in manifest.items():
            f.seek(m["offset"])
            f.write(params[name].detach().cpu().numpy().astype(np.float32).tobytes())
    s = ort.InferenceSession(os.path.join(d, "model.onnx"), providers=["CPUExecutionProvider"])
    m2 = 120
    out = s.run(None, {
        "phone": np.random.rand(1, m2, 768).astype(np.float32),
        "phone_lengths": np.array([m2], dtype=np.int64),
        "pitch": np.random.randint(5, 255, (1, m2)).astype(np.int64),
        "pitchf": (np.random.rand(1, m2) * 300 + 80).astype(np.float32),
        "ds": np.array([0], dtype=np.int64),
        "rnd": np.random.rand(1, 192, m2).astype(np.float32) * 0.66666,
    })[0]
    assert out.shape[-1] == m2 * sr // 100, f"[{tid}] длина выхода {out.shape}"
    os.replace(os.path.join(d, "model.onnx"), os.path.join(OUT, f"{tid}.onnx"))
    print(f"[{tid}] ok: {len(manifest)} тензоров, {total/1e6:.1f} МБ весов, выход {out.shape}")
