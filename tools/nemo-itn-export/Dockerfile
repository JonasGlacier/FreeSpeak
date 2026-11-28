FROM python:3.11.11-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ARG NEMO_REVISION=ddadfb2a38d2bc6b8cc6232c4f915eb60f500688
ARG WETEXT_REVISION=94ab845bc83330a4819a799831462d6f7a7b93ab
# Pynini wheels contain OpenFST; Python, Pynini, NeMo and WeTextProcessing are build-time only.
RUN pip install --no-cache-dir \
        pynini==2.1.6.post1 \
        "setuptools>=65.5.1" \
        "setuptools_scm[toml]>=6.2" \
        wheel \
        importlib_resources \
    && git clone https://github.com/NVIDIA/NeMo-text-processing.git /opt/nemo \
    && git -C /opt/nemo checkout "$NEMO_REVISION" \
    && pip install --no-cache-dir /opt/nemo \
    && git clone https://github.com/wenet-e2e/WeTextProcessing.git /opt/wetext \
    && git -C /opt/wetext checkout "$WETEXT_REVISION" \
    && pip install --no-cache-dir --no-build-isolation /opt/wetext
COPY export_itn.py /opt/export_itn.py
ENTRYPOINT ["python", "/opt/export_itn.py"]
