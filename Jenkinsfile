// Jenkins equivalent of .github/workflows/ci-cd.yml (declarative pipeline).
// Requires: Docker on the agent, JDK 21, Python 3.12, AWS CLI, and credentials
//   'aws-deploy' (AWS credentials binding or an instance role on the agent).
pipeline {
    agent any
    options {
        timestamps()
        timeout(time: 60, unit: 'MINUTES')
        disableConcurrentBuilds()
    }
    environment {
        AWS_REGION  = 'ap-south-1'
        ENVIRONMENT = 'prod'
        IMAGE_TAG   = "${env.GIT_COMMIT}"
    }
    stages {
        stage('Checkout') {
            steps { checkout scm }
        }
        stage('Build & test Java') {
            steps {
                dir('core-service') {
                    sh 'mvn -B -ntp -DskipTests compile checkstyle:check spotbugs:check'
                    sh 'mvn -B -ntp verify'
                }
            }
            post {
                always {
                    junit 'core-service/target/surefire-reports/*.xml, core-service/target/failsafe-reports/*.xml'
                }
            }
        }
        stage('Build & test Python') {
            steps {
                dir('analytics-service') {
                    sh '''
                        python3 -m venv .venv && . .venv/bin/activate
                        pip install -q -r requirements.txt -r requirements-dev.txt
                        ruff check .
                        pytest -q --junitxml=pytest.xml
                    '''
                }
            }
            post {
                always { junit 'analytics-service/pytest.xml' }
            }
        }
        stage('Build & test web frontend') {
            steps {
                dir('frontend') {
                    sh 'npm ci && npm run lint && npm run typecheck && npm test && npx vite build'
                }
            }
        }
        stage('Build images & end-to-end test') {
            steps {
                sh './scripts/generate-env.sh'
                sh 'docker compose up -d --build --wait --wait-timeout 300'
                sh '''
                    set -a; . ./.env; set +a
                    BASE_URL=http://localhost CHECK_ANALYTICS=true \
                    ADMIN_EMAIL="$BOOTSTRAP_ADMIN_EMAIL" ADMIN_PASSWORD="$BOOTSTRAP_ADMIN_PASSWORD" \
                    python3 scripts/smoke_test.py
                '''
            }
            post {
                always { sh 'docker compose down -v || true' }
            }
        }
        stage('Push images') {
            when { branch 'main' }
            steps {
                sh '''
                    REGISTRY=$(aws sts get-caller-identity --query Account --output text).dkr.ecr.${AWS_REGION}.amazonaws.com
                    aws ecr get-login-password --region ${AWS_REGION} | docker login --username AWS --password-stdin $REGISTRY
                    for svc in core analytics web; do
                      docker tag gymops-$svc:${IMAGE_TAG} $REGISTRY/gymops/$svc-service:${IMAGE_TAG}
                      docker push $REGISTRY/gymops/$svc-service:${IMAGE_TAG}
                    done
                '''
            }
        }
        stage('Deploy to AWS') {
            when { branch 'main' }
            steps {
                input message: 'Deploy to production?'
                sh '''
                    REGISTRY=$(aws sts get-caller-identity --query Account --output text).dkr.ecr.${AWS_REGION}.amazonaws.com
                    for svc in core analytics web; do
                      family=gymops-${ENVIRONMENT}-$svc
                      aws ecs describe-task-definition --task-definition $family --query taskDefinition > td.json
                      jq --arg IMG "$REGISTRY/gymops/$svc-service:${IMAGE_TAG}" '.containerDefinitions[0].image = $IMG
                        | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities, .registeredAt, .registeredBy)' \
                        td.json > td-new.json
                      arn=$(aws ecs register-task-definition --cli-input-json file://td-new.json --query taskDefinition.taskDefinitionArn --output text)
                      aws ecs update-service --cluster gymops-${ENVIRONMENT} --service $svc-service --task-definition $arn > /dev/null
                    done
                    aws ecs wait services-stable --cluster gymops-${ENVIRONMENT} --services core-service analytics-service web-service
                '''
            }
        }
        stage('Health checks') {
            when { branch 'main' }
            steps { sh './scripts/post_deploy_check.sh "$PUBLIC_BASE_URL"' }
        }
    }
}
