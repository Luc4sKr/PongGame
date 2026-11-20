using System.Text.RegularExpressions;

namespace PongGame.Auth.Domain;

public sealed record Username
{
    public const string Format = "[A-Za-z0-9_-]{3,32}";
    public string Value { get; private set; } = null!;

    private Username() { }

    public bool Equals(Username? other) =>
        other is not null && StringComparer.OrdinalIgnoreCase.Equals(Value, other.Value);

    public override int GetHashCode() => StringComparer.OrdinalIgnoreCase.GetHashCode(Value);

    public static Username Parse(string value)
    {
        if (string.IsNullOrEmpty(value) || !Regex.IsMatch(value, @"\A" + Format + @"\z"))
            throw new ArgumentException("Nome de usuário inválido.", nameof(value));
        return new Username { Value = value };
    }
}
