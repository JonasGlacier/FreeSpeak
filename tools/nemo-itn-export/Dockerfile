FROM python:3.11.11-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
ARG NEMO_REVISION=ddadfb2a38d2bc6b8cc6232c4f915eb60f500688
# Pynini wheels contain OpenFST; Python and Pynini are build-time only.
RUN pip install --no-cache-dir pynini==2.1.6.post1 \
    && git clone https://github.com/NVIDIA/NeMo-text-processing.git /opt/nemo \
    && git -C /opt/nemo checkout "$NEMO_REVISION" \
    && pip install --no-cache-dir /opt/nemo
COPY export_itn.py /opt/export_itn.py
ENTRYPOINT ["python", "/opt/export_itn.py"]
