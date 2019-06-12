FROM node:latest

RUN mkdir /usr/src/app
WORKDIR /usr/src/app

ENV PATH /usr/src/app/node_modules/.bin:$PATH
RUN yarn global add react-scripts
COPY . /usr/src/app
RUN yarn install

EXPOSE 3000