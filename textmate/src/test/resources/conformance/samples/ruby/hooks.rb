# frozen_string_literal: true

require "json"

module Hooks
  # Runs a hook if it exists.
  class Runner
    attr_reader :name

    def initialize(name, timeout: 30)
      @name = name
      @timeout = timeout
    end

    def run(*args)
      path = File.join(".git", "hooks", name)
      return :missing unless File.executable?(path)

      output = `#{path} #{args.join(" ")}`
      $?.success? ? :ok : { status: $?.exitstatus, output: output }
    end
  end
end

%w[pre-commit commit-msg].each { |hook| puts Hooks::Runner.new(hook).run }
[1, 2, 3].map do |n|
  n * 2
end
