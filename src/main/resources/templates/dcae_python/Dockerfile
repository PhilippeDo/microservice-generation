FROM python:{0}

RUN mkdir /app 
WORKDIR /app  
 
ADD . /app

ENV http_proxy {3}
RUN echo $http_proxy
ENV https_proxy {3}
RUN echo $https_proxy
RUN echo "nameserver 8.8.8.8" | tee /etc/resolv.conf > /dev/null

#--proxy http://pb00365848:!PB123456a%40@10.254.40.121:8080
RUN apt-get clean && apt-get update && apt-get -y install libgtk2.0-dev && apt-get install -y git

RUN pip install -r /app/requirements.txt && \
   pip install acumos_dcae_model_runner

ENV http_proxy=""
RUN echo $http_proxy
ENV https_proxy=""
RUN echo $https_proxy


ENV DCAEPORT=10000
EXPOSE $DCAEPORT

ENTRYPOINT ["acumos_dcae_model_runner"]
CMD ["/app"]