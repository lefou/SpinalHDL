# Copyright 2023 by the SpinalHDL Docker contributors
# SPDX-License-Identifier: GPL-3.0-only
#
# Author(s): Pavel Benacek <pavel.benacek@gmail.com>

FROM ubuntu:22.04

ARG SPINAL_DIR=/data

# Install tools and other stuff
RUN apt update && apt upgrade -y && \
    DEBIAN_FRONTEND=noninteractive apt install -y \
    autoconf \
    build-essential \
    curl \
    git \
    gnupg2 \
    gtkwave \
    mc \
    openjdk-8-jdk \
    scala \
    software-properties-common \
    sudo \
    tzdata \
    ghdl \
    iverilog \
    xauth \
    && apt clean

RUN DEBIAN_FRONTEND=noninteractive apt install -y \
    pkg-config \
    clang \
    tcl-dev \
    libreadline-dev \
    flex \
    bison \
    && apt clean

RUN DEBIAN_FRONTEND=noninteractive apt install -y \
    python3 \
    python3-pip \
    python3-pip-whl \
    && apt clean
RUN pip install cocotb cocotb-test click

ARG VERILATOR_VERSION=v4.228
ARG YOSYS_VERSION=master
ARG SYMBIYOSYS_VERSION=master

WORKDIR /tmp

RUN git clone "https://github.com/verilator/verilator" verilator && \
    cd verilator && \
    git checkout "${VERILATOR_VERSION}" && \
    autoconf && \
    ./configure && \
    make -j "$(nproc)" && \
    make install && \
    cd .. && \
    rm -r verilator

RUN git clone https://github.com/YosysHQ/yosys.git yosys && \
    cd yosys && \
    git checkout "${YOSYS_VERSION}" && \
    make -j$(nproc) && \
    make install && \
    cd .. && rm -rf yosys 

# Install Symbiyosys
RUN git clone https://github.com/YosysHQ/SymbiYosys.git SymbiYosys && \
    cd SymbiYosys && \
    git checkout "${SYMBIYOSYS_VERSION}" && \
    make install && \
    cd .. && rm -rf SymbiYosys 

RUN mkdir solver && cd solver
RUN curl -o solvers.zip -sL "https://github.com/GaloisInc/what4-solvers/releases/download/snapshot-20221212/ubuntu-22.04-bin.zip"
RUN unzip solvers.zip && \
    rm solvers.zip && \
    chmod +x * && \
    cp cvc4 /usr/local/bin/cvc4 && \
    cp cvc5 /usr/local/bin/cvc5 && \
    cp z3 /usr/local/bin/z3 && \
    cp yices /usr/local/bin/yices && \
    cp yices-smt2 /usr/local/bin/yices-smt2 && \
    cd .. && rm -rf solver

# Add repos and install sbt 
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
        | gpg2 --dearmour -o /usr/share/keyrings/sdb-keyring.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
        | tee /etc/apt/sources.list.d/sbt.list \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian /" \
        | tee /etc/apt/sources.list.d/sbt_old.list \
    && apt update && apt install sbt

WORKDIR $SPINAL_DIR

ARG JAVA_EXTRA_OPTS="-Xmx2g -Xms2g"
ENV JAVA_OPTS="${JAVA_OPTS} ${JAVA_EXTRA_OPTS}"
RUN git clone https://github.com/SpinalHDL/SpinalHDL.git && \ 
    cd SpinalHDL && \
    git submodule update --init --recursive && \
    sbt compile

RUN DEBIAN_FRONTEND=noninteractive apt purge -y \
    pkg-config \
    clang \
    tcl-dev \
    libreadline-dev \
    flex \
    bison \
    && apt autoremove -y && apt clean

CMD ["bash"]
