FROM node:20-alpine
WORKDIR /app
COPY package.json tsconfig.json ./
RUN npm install
COPY orchestrator.ts ./
RUN npm run build
ENV PORT=3000
EXPOSE 3000
CMD ["npm", "start"]
