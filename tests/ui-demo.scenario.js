module.exports = {
  name: "organization scan walkthrough",
  // A public GitHub account with a small number of public repositories. The
  // walkthrough reads the repository count from the confirmation dialog rather
  // than hard-coding it, so the recording survives changes to the account.
  owner: "https://github.com/andrey-mogilev",
  screenshots: {
    confirmation: "confirmation",
    progress: "progress",
    completed: "completed",
    alreadyScanned: "already-scanned",
    skipped: "skipped",
    rescanned: "rescanned",
    skills: "skills"
  }
};
