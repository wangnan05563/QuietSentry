import sys
import cv2
import numpy as np

payload = sys.argv[1]
out = sys.argv[2]

params = cv2.QRCodeEncoder_Params()
params.correction_level = cv2.QRCODE_ENCODER_CORRECT_LEVEL_M
encoder = cv2.QRCodeEncoder_create(params)
qr = encoder.encode(payload)
qr = cv2.copyMakeBorder(qr, 4, 4, 4, 4, cv2.BORDER_CONSTANT, value=255)
qr = cv2.resize(qr, (420, 420), interpolation=cv2.INTER_NEAREST)
canvas = np.full((720, 720), 255, np.uint8)
canvas[150:570, 150:570] = qr
if not cv2.imwrite(out, canvas):
    raise SystemExit("write failed")
print(f"{out} {canvas.shape} modules={encoder.encode(payload).shape}")
