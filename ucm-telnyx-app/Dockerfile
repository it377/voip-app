FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

ENV FLASK_ENV=production
EXPOSE 3000

# Single worker: the in-memory WebSocket client set and the JSON message
# store are process-local, so more workers would silently miss broadcasts
# and risk file-write races. See README for scaling this beyond one process.
CMD ["gunicorn", "--bind", "0.0.0.0:3000", "--workers", "1", "--worker-class", "gthread", "--threads", "8", "app:app"]
