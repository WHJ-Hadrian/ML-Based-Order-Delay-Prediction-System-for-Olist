FROM python:3.12-slim

WORKDIR /opt/mlflow
COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
USER 10001
EXPOSE 5000
