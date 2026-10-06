import os

# OpenCV 자체 해상도 상한 (app.core.detection.MAX_PIXELS와 같은 값). cv2를 import하기 전에 설정해야 한다.
os.environ.setdefault("OPENCV_IO_MAX_IMAGE_PIXELS", "12000000")
