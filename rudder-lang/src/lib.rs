// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

// Found no other way to import log and its macros
#[macro_use]
extern crate log;

#[macro_use]
pub mod error;
mod ast;
pub mod compile;
mod generators;
pub mod logger;
mod parser;
pub mod translate;
pub mod generate_oslib;
