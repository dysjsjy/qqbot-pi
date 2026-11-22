# tool/
> L2 | Parent: ../AGENTS.md

- `count_code.py`：按代码类型统计文件数和物理行数。只读扫描，不依赖 Git 或第三方库。

使用 Python 3.10 或更高版本。执行 `python tool/count_code.py` 统计项目根目录。传入目录路径时，只统计该目录。
统计包含 Java 回归源码和部署配置，不包含依赖、锁文件、正式版之外的验收工具、过程目录与生成产物。
显式入口也检查排除目录。项目内入口只检查项目根下的路径；外部入口检查完整路径。
源码按 UTF-8 读取。非空行包含注释。末行没有换行符时仍计为一行。

[PROTOCOL]: Update this header on change, then check AGENTS.md
