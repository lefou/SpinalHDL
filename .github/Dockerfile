# Copyright 2023 by the SpinalHDL Docker contributors
# SPDX-License-Identifier: GPL-3.0-only
#
# Author(s): Pavel Benacek <pavel.benacek@gmail.com>

ARG UBUNTU_VERSION=22.04
FROM ubuntu:$UBUNTU_VERSION AS builder

ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8 PREFIX=/opt
ARG SPINAL_DIR=/data

ARG DEPS_COMMON="autoconf build-essential ca-certificates curl git clang python3"
RUN apt-get update && \
    apt-get install -y --no-install-recommends $DEPS_COMMON

FROM builder AS build-symbiyosys

ARG DEPS_YOSYS="libffi-dev libreadline-dev pkg-config tcl-dev unzip flex bison"
RUN apt-get install -y --no-install-recommends $DEPS_YOSYS

ARG YOSYS_VERSION="yosys-0.28"
RUN git clone https://github.com/YosysHQ/yosys.git yosys && \
    cd yosys && \
    git checkout $YOSYS_VERSION && \
    make PREFIX=$PREFIX -j$(nproc) && \
    make PREFIX=$PREFIX install && \
    cd .. && \
    rm -Rf yosys

ARG SOLVERS_PATH="snapshot-20221212/ubuntu-22.04-bin.zip"
RUN mkdir solver && cd solver && \
    curl -o solvers.zip -sL "https://github.com/GaloisInc/what4-solvers/releases/download/${SOLVERS_PATH}" && \
    unzip solvers.zip && \
    rm solvers.zip && \
    chmod +x * && \
    cp cvc4 $PREFIX/bin/cvc4 && \
    cp cvc5 $PREFIX/bin/cvc5 && \
    cp z3 $PREFIX/bin/z3 && \
    cp yices $PREFIX/bin/yices && \
    cp yices-smt2 $PREFIX/bin/yices-smt2 && \
    cd .. && rm -rf solver

ARG SYMBIYOSYS_VERSION="yosys-0.28"
RUN git clone https://github.com/YosysHQ/sby.git SymbiYosys && \
    cd SymbiYosys && \
    git checkout $SYMBIYOSYS_VERSION && \
    make PREFIX=$PREFIX -j$(nproc) install && \
    cd .. && \
    rm -Rf SymbiYosys

FROM builder AS build-verilator

ARG DEPS_VERILATOR="perl python3 make autoconf g++ flex bison ccache libgoogle-perftools-dev numactl perl-doc libfl2 libfl-dev zlib1g zlib1g-dev"
RUN apt-get install -y --no-install-recommends $DEPS_VERILATOR

ARG VERILATOR_VERSION="v4.228"
RUN git clone https://github.com/verilator/verilator verilator && \
    cd verilator && \
    git checkout $VERILATOR_VERSION && \
    autoconf && \
    ./configure --prefix $PREFIX && \
    make PREFIX=$PREFIX -j$(nproc) && \
    make PREFIX=$PREFIX install && \
    cd ../.. && \
    rm -Rf verilator

FROM builder AS build-spinal

ENV COURSIER_CACHE="/coursier/.cache"
ENV SBT_OPTS="-Dsbt.override.build.repos=true -Dsbt.boot.directory=/sbt/.sbt/boot -Dsbt.global.base=/sbt/.sbt -Dsbt.ivy.home=/sbt/.ivy2 -Duser.home=/sbt -Dsbt.global.localcache=/sbt/.sbt/cache"

ARG DEPS_SPINAL="gnupg2 openjdk-11-jdk-headless"
RUN apt-get install -y --no-install-recommends $DEPS_SPINAL
# Add repos and install sbt 
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
        | gpg2 --dearmour -o /usr/share/keyrings/sdb-keyring.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
        | tee /etc/apt/sources.list.d/sbt.list \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian /" \
        | tee /etc/apt/sources.list.d/sbt_old.list \
    && apt update && apt install sbt

ARG MILL_VERSION="0.10.9"
RUN \
  curl -L -o /usr/local/bin/mill https://github.com/lihaoyi/mill/releases/download/$MILL_VERSION/$MILL_VERSION && \
  chmod +x /usr/local/bin/mill && \
  touch build.sc && \
  mill -i resolve _ && \
  rm build.sc

ARG JAVA_EXTRA_OPTS="-Xmx2g -Xms2g"
ENV JAVA_OPTS="${JAVA_OPTS} ${JAVA_EXTRA_OPTS}"
RUN git clone https://github.com/SpinalHDL/SpinalHDL.git && \ 
    cd SpinalHDL && \
    git submodule update --init --recursive && \
    sbt compile && \
    mill _.compile && \
    cd .. && rm -rf SpinalHDL

FROM ubuntu:$UBUNTU_VERSION AS run

ENV DEBIAN_FRONTEND=noninteractive LANG=C.UTF-8 LC_ALL=C.UTF-8 PATH="$PATH:/opt/bin"

ARG DEPS_RUNTIME="ca-certificates gnupg2 openjdk-11-jdk-headless ccache curl g++ gcc git libtcl8.6 python3 python3-pip python3-pip-whl libpython3-dev ssh locales make ghdl iverilog libboost1.74-dev"
RUN apt-get update && \
    apt-get install -y --no-install-recommends $DEPS_RUNTIME && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/* && \
    locale-gen en_US.UTF-8
RUN pip install cocotb cocotb-test click

# Add repos and install sbt 
RUN curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
        | gpg2 --dearmour -o /usr/share/keyrings/sdb-keyring.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
        | tee /etc/apt/sources.list.d/sbt.list \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/sdb-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian /" \
        | tee /etc/apt/sources.list.d/sbt_old.list \
    && apt update && apt install sbt && rm -rf /var/lib/apt/lists/*

COPY --from=build-symbiyosys /opt /opt
COPY --from=build-verilator /opt /opt
COPY --from=build-spinal /opt /opt
COPY --from=build-spinal /usr/local/bin/mill /opt/bin/mill
COPY --from=build-spinal /coursier /coursier
COPY --from=build-spinal /sbt /sbt