# Copyright 2023 by the SpinalHDL Docker contributors
# SPDX-License-Identifier: GPL-3.0-only
#
# Author(s): Pavel Benacek <pavel.benacek@gmail.com>

FROM ubuntu:22.04

ARG USER=user
ARG UID=1000
ARG GID=1000
ARG PASS=password
ARG SPINAL_DIR=SPINAL

# Install tools and other stuff
RUN apt update && apt upgrade -y && \
    DEBIAN_FRONTEND=noninteractive apt install -y \
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
    verilator \
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

RUN mkdir -p /home/solvers
WORKDIR /home/solvers

RUN git clone https://github.com/YosysHQ/yosys.git yosys && \
    cd yosys && \
    make -j$(nproc) && \
    make install && \
    cd .. && rm -rf yosys 

# Install Symbiyosys
RUN git clone https://github.com/YosysHQ/SymbiYosys.git SymbiYosys && \
    cd SymbiYosys && \
    make install && \
    cd .. && rm -rf SymbiYosys 

RUN curl -o solvers.zip -sL "https://github.com/GaloisInc/what4-solvers/releases/download/snapshot-20221212/ubuntu-22.04-bin.zip"
RUN unzip solvers.zip && \
    rm solvers.zip && \
    chmod +x * && \
    cp cvc4 /usr/local/bin/cvc4 && \
    cp cvc5 /usr/local/bin/cvc5 && \
    cp z3 /usr/local/bin/z3 && \
    cp yices /usr/local/bin/yices && \
    cp yices-smt2 /usr/local/bin/yices-smt2

# Add repos and install sbt 
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
        | gpg2 --dearmour -o /usr/share/keyrings/sdb-keyring.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
        | tee /etc/apt/sources.list.d/sbt.list \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian /" \
        | tee /etc/apt/sources.list.d/sbt_old.list \
    && apt update && apt install sbt

# Add user into the system
RUN groupadd --gid $GID $USER && \
    useradd --uid $UID --gid $GID --groups sudo --shell /bin/bash -m $USER && \
    echo "$USER ALL=(ALL) NOPASSWD: ALL" > /etc/sudoers.d/admins && \
    echo "$USER:$PASS" | chpasswd


# Copy downloaded stuff to SPINAL folder
#COPY $SPINAL_DIR /SPINAL

#ENTRYPOINT [ "/usr/bin/bash" ]
#ADD entrypoint.sh /
#ENTRYPOINT ["/entrypoint.sh"]
