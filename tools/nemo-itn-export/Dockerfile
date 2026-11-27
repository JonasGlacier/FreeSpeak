FROM python:3.11.11-slim-bookworm
# NeMo's cdifflib dependency builds a C extension from source. The slim Python
# image provides Python headers, but not gcc or the system development headers.
RUN apt-get update && apt-get install -y --no-install-recommends git ca-certificates build-essential \
    && rm -rf /var/lib/apt/lists/*
ARG NEMO_REVISION=ddadfb2a38d2bc6b8cc6232c4f915eb60f500688
# Pynini wheels contain OpenFST; Python and Pynini are build-time only.
RUN pip install --no-cache-dir pynini==2.1.6.post1
RUN git clone https://github.com/NVIDIA/NeMo-text-processing.git /opt/nemo \
    && git -C /opt/nemo checkout "$NEMO_REVISION"
RUN pip install --no-cache-dir /opt/nemo \
    && pip check
# Fail during image construction if native extensions or NeMo imports are broken.
RUN python -c "import cdifflib, pynini; from nemo_text_processing.inverse_text_normalization.inverse_normalize import InverseNormalizer"
COPY export_itn.py /opt/export_itn.py
ENTRYPOINT ["python", "/opt/export_itn.py"]
