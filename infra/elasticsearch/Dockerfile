FROM docker.elastic.co/elasticsearch/elasticsearch:9.4.5

# 官方外掛與基底映像一起固定版本，避免節點重啟時才下載。
RUN bin/elasticsearch-plugin install --batch analysis-smartcn
