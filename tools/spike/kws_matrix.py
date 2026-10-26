import itertools, numpy as np, sherpa_onnx, soundfile as sf, tempfile, os
M="sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
pos=["hey_hermes_en.wav","en_hey_hermes2.wav","hr_hej_hermes2.wav","hej_hermes_hr.wav","hr_hermes.wav","hr_hermes_tu.wav"]
neg=["negativan.wav"]
aud={p:sf.read(p,dtype="float32") for p in pos+neg}
def run(kwfile):
    kws=sherpa_onnx.KeywordSpotter(tokens=f"{M}/tokens.txt",
        encoder=f"{M}/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        decoder=f"{M}/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        joiner=f"{M}/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
        keywords_file=kwfile,num_threads=2,provider="cpu")
    out={}
    for p,(a,sr) in aud.items():
        tail=np.zeros(int(0.8*sr),dtype=np.float32); s=kws.create_stream()
        s.accept_waveform(sr,a); s.accept_waveform(sr,tail); s.input_finished(); hits=[]
        while kws.is_ready(s):
            kws.decode_stream(s); r=kws.get_result(s)
            if r: hits.append(r); kws.reset_stream(s)
        out[p]=hits
    return out
variants={
 "hey":"▁HE Y ▁HER ME S",
 "hej":"▁HE J ▁HER ME S",
 "hermes":"▁HER ME S",
 "hi":"▁HI ▁HER ME S",
 "ok":"▁O K ▁HER ME S",
}
for name,toks in variants.items():
    for score,thr in [(1.0,0.25),(2.0,0.15),(3.0,0.1),(4.0,0.05)]:
        f=tempfile.NamedTemporaryFile('w',suffix='.txt',delete=False)
        f.write(f"{toks} :{score} #{thr}\n"); f.close()
        r=run(f.name); os.unlink(f.name)
        print(f"{name:7s} score={score} thr={thr}: " + " | ".join(f"{os.path.basename(p)}={'Y' if h else '.'}" for p,h in r.items()))
