FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY reddit_proxy.py test_reddit_proxy.py ./
USER 65532:65532
EXPOSE 8787
HEALTHCHECK --interval=60s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8787/healthz', timeout=4)"
CMD ["uvicorn", "reddit_proxy:app", "--host", "0.0.0.0", "--port", "8787", "--no-access-log"]
