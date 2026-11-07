FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app

RUN addgroup --system bridge && adduser --system --ingroup bridge bridge

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY enchat_bridge ./enchat_bridge

USER bridge

CMD ["python", "-m", "enchat_bridge.main"]
