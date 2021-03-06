// SPDX-License-Identifier: GPL-3.0-or-later
// SPDX-FileCopyrightText: 2019-2020 Normation SAS

const TESTFILES_PATH: &'static str = "tests/test_files";
const RLFILES_PATH: &'static str = "tests/test_files/rl";
const VIRTUALFILES_PATH: &'static str = "tests/test_files/tmp";

use colored::Colorize;
use std::{
    fs,
    io::Write,
    path::{Path, PathBuf},
};

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Generates a file from a string and tests it
pub fn test_generated_file(filename: &str, content: &str) {
    fs::create_dir_all(VIRTUALFILES_PATH)
        .expect(&format!("Could not create {} path", TESTFILES_PATH));
    let path = PathBuf::from(format!("{}/{}.rl", VIRTUALFILES_PATH, filename));
    let mut file = fs::File::create(&path).expect("Could not create file");
    file.write_all(content.as_bytes())
        .expect("Could not write to file");
    test_file(&path, &path, filename);
    fs::remove_file(path).expect("Could not delete temporary file");
}

/// Paired with `test_case` proc-macro calls from the `compile.rs` test file.
/// Tests the file that matches the `filename` argument
pub fn test_real_file(filename: &str, dest_folder: &str) {
    fs::create_dir_all(format!("{}/{}", TESTFILES_PATH, dest_folder))
        .expect(&format!("Could not create /test_files/{} dir", dest_folder));
    let input_path = PathBuf::from(format!("{}/{}.rl", RLFILES_PATH, filename));
    let output_path = PathBuf::from(format!(
        "{}/{}/{}.rl",
        TESTFILES_PATH, dest_folder, filename
    ));
    test_file(&input_path, &output_path, filename);
}

/// Core test function that actually compares the file compilation result to expected result
fn test_file(input_path: &Path, output_path: &Path, filename: &str) {
    let result = compile_file(&input_path, &output_path, filename);
    assert_eq!(
        result.is_ok(),
        should_compile(filename),
        "{}: {} assertion is not true. Compiler result (lhs) differs from expectations (rhs)",
        "\nError (test)".bright_red().bold(),
        filename.bright_yellow(),
    );
}

/// Tool function extracting expected compilation result
fn should_compile(filename: &str) -> bool {
    return match &filename[..2] {
        "s_" => true,
        "f_" => false,
        _ => panic!(
            "{}: file naming rules are not respected for {}, cannot assert test",
            "Warning (test)".bright_yellow().bold(),
            filename.bright_yellow(),
        ),
    };
}

/// Compile technique from base crate and expose its result
fn compile_file(input_path: &Path, output_path: &Path, filename: &str) -> Result<(), String> {
    match rudderc::compile::compile_file(input_path, output_path, true) {
        Ok(_) => {
            println!(
                "{}: compilation of {}",
                "Success (rudderc)".bright_green().bold(),
                filename.bright_yellow()
            );
            Ok(())
        }
        Err(rudderc::error::Error::User(e)) => {
            println!(
                "{}: compilation of {} failed: {}",
                "Error (rudderc)".bright_red().bold(),
                filename.bright_yellow(),
                e
            );
            Err(e)
        },
        Err(rudderc::error::Error::List(e)) => {
            println!(
                "{}: compilation of {} failed: {:#?}",
                "Error (rudderc)".bright_red().bold(),
                filename.bright_yellow(),
                e
            );
            Err(e.join("\n"))
        }
    }
}
