# PP-OCRv6 small with detection capped at 1600 px on the longer side: the same pinned models as
# pp-ocrv6-small, about half the time and a third of the memory on 300 dpi pages (OCR.md).
format 1
id pp-ocrv6-small-balanced
file detector det.onnx d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.onnx
file detector_config det.yml 193f435274bf9f0b5f71a929bbfbcf148282df7e633b34e7c373e8f44741b516 https://huggingface.co/PaddlePaddle/PP-OCRv6_small_det_onnx/resolve/28fe5895c24fd108c19eb3e8479f4ab385fbfc62/inference.yml
file recognizer rec.onnx 5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634 https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.onnx
file recognizer_config rec.yml ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1 https://huggingface.co/PaddlePaddle/PP-OCRv6_small_rec_onnx/resolve/b8f84f0b80c529de40b4fbb3544b84fa7233a513/inference.yml
derived dictionary dictionary.txt b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d
param det_limit_side_len 1600
param det_limit_type max
param det_max_side_limit 4000
param det_thresh 0.3
param det_box_thresh 0.6
param det_unclip_ratio 1.5
param det_max_candidates 3000
param rec_height 48
param rec_min_width 320
param rec_max_width 3200
param rec_batch_size 6
