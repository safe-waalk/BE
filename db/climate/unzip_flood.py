#!/usr/bin/env python3
"""침수흔적도 zip → flood_<연도>_<n>.{shp,shx,dbf,prj,cpg}. 표준 라이브러리만 쓴다.

zip 안 파일명이 CP949 한글이라 unzip으로 풀면 이름이 깨진다. 원래 이름은 쓰지 않고
같은 stem끼리 묶어 ASCII 이름으로 풀며, 만든 .shp 경로를 한 줄씩 출력한다.
사용: python3 -I db/climate/unzip_flood.py <zip> <출력 디렉터리> <연도>
"""

import collections
import os
import sys
import zipfile

SHAPE_PARTS = ("shp", "shx", "dbf", "prj", "cpg")


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    zpath, outdir, year = sys.argv[1:]
    os.makedirs(outdir, exist_ok=True)
    groups = collections.defaultdict(dict)
    with zipfile.ZipFile(zpath) as z:
        for info in z.infolist():
            stem, ext = os.path.splitext(info.filename)
            ext = ext.lower().lstrip(".")
            if not info.is_dir() and ext in SHAPE_PARTS:
                groups[stem][ext] = info
        stems = sorted(s for s in groups if "shp" in groups[s])
        for i, stem in enumerate(stems):
            base = os.path.join(outdir, f"flood_{year}_{i}")
            for ext in SHAPE_PARTS:
                # 이전 실행에서 남은 .prj/.cpg가 이번 zip에는 없을 수 있으니 지운다(우리가 만든 파일만)
                if ext not in groups[stem] and os.path.exists(f"{base}.{ext}"):
                    os.remove(f"{base}.{ext}")
            for ext, info in groups[stem].items():
                with z.open(info) as src, open(f"{base}.{ext}", "wb") as dst:
                    dst.write(src.read())
            print(f"{base}.shp")


if __name__ == "__main__":
    main()
