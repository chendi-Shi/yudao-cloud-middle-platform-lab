#!/usr/bin/env bash
# 本机联调启动脚本（yudao-cloud master-jdk17）
# 依赖：script/docker-compose 下的 mysql / redis / nacos 已启动（映射 13306 / 16379 / 8848）
set -e
BASE="$(cd "$(dirname "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-$BASE/../.tools/jdk-17.0.21+9/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"

# MySQL / Redis 指向 docker 映射端口
export SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL="jdbc:mysql://127.0.0.1:13306/ruoyi-vue-pro?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&nullCatalogMeansCurrent=true&rewriteBatchedStatements=true"
export SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_USERNAME=root
export SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_PASSWORD='Root@Yudao2026'
export SPRING_DATASOURCE_DYNAMIC_DATASOURCE_SLAVE_URL="$SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL"
export SPRING_DATASOURCE_DYNAMIC_DATASOURCE_SLAVE_USERNAME=root
export SPRING_DATASOURCE_DYNAMIC_DATASOURCE_SLAVE_PASSWORD='Root@Yudao2026'
export SPRING_DATA_REDIS_HOST=127.0.0.1
export SPRING_DATA_REDIS_PORT=16379
export SPRING_DATA_REDIS_PASSWORD='Redis@Yudao2026'

# 本地没装 Nacos 命名空间 dev，统一用 public；未部署 XXL-Job，关闭执行器；关闭演示模式
export SPRING_CLOUD_NACOS_DISCOVERY_NAMESPACE=public
export SPRING_CLOUD_NACOS_CONFIG_NAMESPACE=public
export XXL_JOB_ENABLED=false
export YUDAO_DEMO=false
export YUDAO_CAPTCHA_ENABLE=false

# 网关治理：限流 + 内部签名（MODULE=gateway 时生效）
export INTERNAL_SIGN_SECRET='yudao-internal-sign-secret-local'
export YUDAO_GATEWAY_RATE_LIMIT_ENABLED=${YUDAO_GATEWAY_RATE_LIMIT_ENABLED:-true}
export YUDAO_GATEWAY_RATE_LIMIT_DEFAULT_QPS=${YUDAO_GATEWAY_RATE_LIMIT_DEFAULT_QPS:-100}
export YUDAO_GATEWAY_RATE_LIMIT_DEFAULT_CAPACITY=${YUDAO_GATEWAY_RATE_LIMIT_DEFAULT_CAPACITY:-200}
export YUDAO_GATEWAY_INTERNAL_SIGN_ENABLED=true
# 服务端：校验网关签名
export YUDAO_SECURITY_INTERNAL_SIGN_ENABLED=true
export YUDAO_SECURITY_INTERNAL_SIGN_SECRET="$INTERNAL_SIGN_SECRET"

MODULE="$1"
# ★ 关键：日志默认写到 ~/logs（沙箱不可写），改到仓库内 logs/ 目录
mkdir -p "$BASE/logs" "$BASE/home/logs"
export LOGGING_FILE_NAME="$BASE/logs/$1.log"
case "$MODULE" in
  system) JAR=yudao-module-system/yudao-module-system-server/target/yudao-module-system-server.jar ;;
  infra)  JAR=yudao-module-infra/yudao-module-infra-server/target/yudao-module-infra-server.jar ;;
  gateway) JAR=yudao-gateway/target/yudao-gateway.jar ;;
  *) echo "usage: $0 {system|infra|gateway}"; exit 1 ;;
esac
exec java -Xms256m -Xmx512m -Duser.home="$BASE/home" -Dnacos.logging.path="$BASE/logs/nacos" -jar "$BASE/$JAR" --spring.profiles.active=dev
