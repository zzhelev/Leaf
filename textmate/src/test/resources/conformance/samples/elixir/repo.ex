defmodule Example.Repo do
  @moduledoc """
  Runs git in a repository.
  """

  @timeout 30_000

  defstruct [:path, branch: "main"]

  @doc "Lists the branches."
  @spec branches(%__MODULE__{}) :: {:ok, [String.t()]} | {:error, term()}
  def branches(%__MODULE__{path: path}) do
    case System.cmd("git", ["branch", "--format=%(refname:short)"], cd: path) do
      {out, 0} -> {:ok, String.split(out, "\n", trim: true)}
      {err, code} when code > 0 -> {:error, {code, err}}
    end
  end

  def current?(%__MODULE__{branch: b}, name), do: b == name # simple
  def sigil, do: ~r/^refs\/heads\// |> Regex.source()
end
