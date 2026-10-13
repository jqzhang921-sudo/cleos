语音条「在耳边」用到的数据

Neumann KU100 假人头的近场头相关脉冲响应（HRIR）：
J. M. Arend, A. Neidhardt, C. Pörschmann.
Spherical Near-Field (NF) HRIR Compilation of the Neumann KU100.
Zenodo, 2020. https://doi.org/10.5281/zenodo.4297951
许可：CC BY 4.0（https://creativecommons.org/licenses/by/4.0/）

ku100_near.bin 由 binaural-voice（https://github.com/Saekisui/binaural-voice）里的
ku100_nearfield_circ360.npz 转来，那份文件是上面数据集中 NFHRIR_CIRC360_SOFA 的格式转换。
Cleos 做了这些改动：
- 只留水平面 0.25、0.5、0.75 米三档（原来是 0.25 到 1.5 米五档）；
- 每一档乘上数据集说明（NF_Datasets_Gains_infos.pdf）给的距离增益：0.25 米 1.0、0.5 米 0.33、0.75 米 0.25；
- 改成 16 位整数存；播放前按语音条自己的采样率重新采样。
转换脚本在仓库的 tools/convert_hrir.py。

语音绕着头走的方式（只在出声时挪、几种随机走法、快慢和轻微晃动）移植自 binaural-voice，MIT 许可全文见 assets/licenses/binaural-voice.txt。数据本身仍采用上述 CC BY 4.0。
