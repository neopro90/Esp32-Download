FROM node:20-bookworm-slim

# Cài đặt Python, FFmpeg, yt-dlp và các công cụ cần thiết
RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 \
    python3-pip \
    ffmpeg \
    curl \
    ca-certificates \
    && pip install --break-system-packages --upgrade yt-dlp gallery-dl \
    && apt-get clean && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Khởi tạo thư mục dữ liệu và tải về
RUN mkdir -p /app/data /app/downloads

COPY . /app

EXPOSE 8787

ENV NODE_ENV=production
ENV PORT=8787

CMD ["node", "server.mjs"]
